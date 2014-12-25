package org.wso2.nio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

/**
 * Receives the connection requests and writes it to the back end service. Then
 * reads the response and send it back to the caller.
 * 
 * @author ravindra
 *
 */
public class ConnectingIOReactor implements Runnable {
	private static final Logger LOGGER = Logger.getLogger(ConnectingIOReactor.class);

	// The host:port combination to listen on
	private InetAddress hostAddress;
	private int port;

	// The selector we'll be monitoring
	private final Selector selector;

	// The buffer into which we'll read data when it's available
	private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

	private List<ChangeRequest> pendingChanges = new LinkedList<ChangeRequest>();

	// Maps a SocketChannel to a list of ByteBuffer instances
	private Map<SocketChannel, List<ByteBuffer>> pendingData =
	                                                           new HashMap<SocketChannel, List<ByteBuffer>>();

	// Maps a SocketChannel to a RspHandler
	private Map<SocketChannel, RspHandler> rspHandlers =
	                                                     Collections.synchronizedMap(new HashMap<SocketChannel, RspHandler>());

	private static ConnectingIOReactor connectingIOReactor = null;

	ConnectingIOReactor(InetAddress hostAddress, int port) throws IOException {
		this.hostAddress = hostAddress;
		this.port = port;
		this.selector = initSelector();
	}

	private Selector initSelector() throws IOException {
		// Create a new selector
		Selector socketSelector = SelectorProvider.provider().openSelector();

		return socketSelector;
	}

	public void run() {
		while (true) {
			try {
				// Process any pending changes
				synchronized (this.pendingChanges) {
					Iterator<ChangeRequest> changes = this.pendingChanges.iterator();
					while (changes.hasNext()) {
						ChangeRequest change = changes.next();
						switch (change.type) {
							case ChangeRequest.CHANGEOPS:
								SelectionKey key = change.socket.keyFor(this.selector);
								key.interestOps(change.ops);
								break;
							case ChangeRequest.REGISTER:
								change.socket.register(this.selector, change.ops);
								break;
						}
					}
					this.pendingChanges.clear();
				}
				// Wait for an event one of the registered channels
				this.selector.select();
				// Iterate over the set of keys for which events are available
				Iterator<SelectionKey> selectedKeys = this.selector.selectedKeys().iterator();
				while (selectedKeys.hasNext()) {
					SelectionKey key = selectedKeys.next();
					selectedKeys.remove();

					if (!key.isValid()) {
						continue;
					}

					// Check what event is available and deal with it
					if (key.isConnectable()) {
						this.finishConnection(key);
					} else if (key.isReadable()) {
						this.read(key);
					} else if (key.isWritable()) {
						this.write(key);
					}

				}

			} catch (Exception e) {
				LOGGER.error("Exception was thrown while Selecting IOEvents.", e);
			}
		}

	}

	private void read(SelectionKey key) throws IOException {
		LOGGER.info("Reading the response from the backend.");
		SocketChannel socketChannel = (SocketChannel) key.channel();

		// Clear out our read buffer so it's ready for new data
		this.readBuffer.clear();

		// Attempt to read off the channel
		int numRead;
		try {
			numRead = socketChannel.read(this.readBuffer);
		} catch (IOException e) {
			LOGGER.error("ERROR while reading the response from the backend", e);
			// The remote forcibly closed the connection, cancel
			// the selection key and close the channel.
			key.cancel();
			socketChannel.close();
			return;
		}

		if (numRead == -1) {
			LOGGER.info("Backend service closes the connection gracefully.");
			// Remote entity shut the socket down cleanly. Do the
			// same from our end and cancel the channel.
			key.channel().close();
			key.cancel();
			return;
		}

		// Handle the response
		this.handleResponse(socketChannel, this.readBuffer.array(), numRead);

	}

	private void handleResponse(SocketChannel socketChannel, byte[] data, int numRead)
	                                                                                  throws IOException {
		// Make a correctly sized copy of the data before handing it
		// to the client
		byte[] rspData = new byte[numRead];
		System.arraycopy(data, 0, rspData, 0, numRead);

		RspHandler handler = this.rspHandlers.get(socketChannel);

		// And pass the response to it
		if (handler.handleResponse(rspData)) {
			// The handler has seen enough, close the connection
			// TODO: Remove this in case if you need a TCP KeepAlive connection.
			socketChannel.close();
			socketChannel.keyFor(this.selector).cancel();
		}
	}

	private void write(SelectionKey key) throws IOException {
		LOGGER.info("Writing the data from the Proxy service to the Backend.");
		SocketChannel socketChannel = (SocketChannel) key.channel();
		synchronized (pendingData) {
			List<ByteBuffer> queue = pendingData.get(socketChannel);

			// Write until there's not more data ...
			while (!queue.isEmpty()) {
				ByteBuffer buffer = queue.get(0);
				socketChannel.write(buffer);

				if (buffer.remaining() > 0) {
					// ... or the socket's buffer fills up
					break;
				}
				queue.remove(0);
			}

			if (queue.isEmpty()) {
				// We wrote away all data, so we're no longer interested
				// in writing on this socket. Switch back to waiting for
				// data. Waiting to read the response here.
				key.interestOps(SelectionKey.OP_READ);
			}
		}

	}

	private SocketChannel initiateConnection() throws IOException {
		// Create a non-blocking socket channel
		SocketChannel socketChannel = SocketChannel.open();
		socketChannel.configureBlocking(false);

		// Kick off connection establishment
		socketChannel.connect(new InetSocketAddress(this.hostAddress, this.port));

		// Queue a channel registration since the caller is not the
		// selecting thread. As part of the registration we'll register
		// an interest in connection events. These are raised when a channel
		// is ready to complete connection establishment.
		synchronized (this.pendingChanges) {
			this.pendingChanges.add(new ChangeRequest(socketChannel, ChangeRequest.REGISTER,
			                                          SelectionKey.OP_CONNECT));
		}

		return socketChannel;
	}

	public void send(byte[] data, RspHandler handler) throws IOException {
		// Start a new connection
		SocketChannel socket = this.initiateConnection();
		// Register the response handler
		this.rspHandlers.put(socket, handler);
		synchronized (this.pendingData) {
			List<ByteBuffer> queue = pendingData.get(socket);
			if (queue == null) {
				queue = new ArrayList<ByteBuffer>();
				this.pendingData.put(socket, queue);
			}
			queue.add(ByteBuffer.wrap(data));

			// Finally, wake up our selecting thread so it can make the required
			// changes
			this.selector.wakeup();
		}
	}

	private void finishConnection(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		// Finish the connection. If the connection operation failed
		// this will raise an IOException.
		try {
			socketChannel.finishConnect();
		} catch (IOException e) {
			LOGGER.error("An Exception was thrown while establishing a connection between the proxy service and the backend.",
			             e);
			// Cancel the channel's registration with our selector
			key.cancel();
			return;
		}

		// Register an interest in writing on this channel
		key.interestOps(SelectionKey.OP_WRITE);
	}

}
