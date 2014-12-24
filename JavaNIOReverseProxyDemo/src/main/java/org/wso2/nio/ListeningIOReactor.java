package org.wso2.nio;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.log4j.Logger;

/**
 * Defines the <code>ioReactor</code> which listens to the incoming client
 * connections and sends the requests to the connecting ioReactor. Also this
 * reactor is responsible for writing the response back to the sender.
 * 
 * @author ravindra
 *
 */
public class ListeningIOReactor implements Runnable {
	private static final Logger LOGGER = Logger.getLogger(ListeningIOReactor.class.getName());

	private Worker worker;

	// The host:port combination to listen on
	private InetAddress hostAddress;
	private int localPort;

	// The channel on which we'll accept connections
	private ServerSocketChannel serverChannel;

	// The selector we'll be monitoring
	private final Selector selector;

	// The buffer into which we'll read data when it's available
	private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

	private List<ChangeRequest> changeRequests = new LinkedList<ChangeRequest>();

	// Maps a SocketChannel to a list of ByteBuffer instances
	private Map<SocketChannel, List<ByteBuffer>> pendingData =
	                                                           new HashMap<SocketChannel, List<ByteBuffer>>();

	public ListeningIOReactor(InetAddress hostAddress, int localPort, Worker worker)
	                                                                                throws IOException {
		this.hostAddress = hostAddress;
		this.localPort = localPort;
		this.selector = this.initSelector();
		this.worker = worker;
	}

	private Selector initSelector() throws IOException {
		LOGGER.info("Initializing the Selector.");
		// Create a new selector
		Selector socketSelector = SelectorProvider.provider().openSelector();

		// Create a new non-blocking server socket channel
		this.serverChannel = ServerSocketChannel.open();
		serverChannel.configureBlocking(false);

		// Bind the server socket to the specified address and port
		InetSocketAddress isa = new InetSocketAddress(this.hostAddress, this.localPort);
		serverChannel.socket().bind(isa);

		// Register the server socket channel, indicating an interest in
		// accepting new connections
		serverChannel.register(socketSelector, SelectionKey.OP_ACCEPT);

		return socketSelector;
	}

	public void run() {
		while (true) {
			try {
				// Process any pending changes
				synchronized (this.changeRequests) {
					Iterator<ChangeRequest> changes = this.changeRequests.iterator();
					while (changes.hasNext()) {
						ChangeRequest change = changes.next();
						switch (change.type) {
							case ChangeRequest.CHANGEOPS:
								SelectionKey key = change.socket.keyFor(this.selector);
								key.interestOps(change.ops);
						}
					}
					this.changeRequests.clear();
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
					if (key.isAcceptable()) {
						this.accept(key);
					} else if (key.isReadable()) {
						this.read(key);
					} else if (key.isWritable()) {
						this.write(key);
					}

				}

			} catch (Exception e) {
				LOGGER.error("Exception was thrown while Selecting IO Events.", e);
			}
		}

	}

	private void write(SelectionKey key) throws IOException {
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
				// data.
				key.interestOps(SelectionKey.OP_READ);
			}
		}

	}

	private void read(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		// Clear out our read buffer so it's ready for new data
		this.readBuffer.clear();

		// Attempt to read off the channel
		int numRead;
		try {
			numRead = socketChannel.read(this.readBuffer);
		} catch (IOException e) {
			// The remote forcibly closed the connection, cancel
			// the selection key and close the channel.
			key.cancel();
			socketChannel.close();
			return;
		}

		if (numRead == -1) {
			// Remote entity shut the socket down cleanly. Do the
			// same from our end and cancel the channel.
			key.channel().close();
			key.cancel();
			return;
		}

		// Hand the data off to our worker thread
		this.worker.processData(this, socketChannel, this.readBuffer.array(), numRead);

	}

	private void accept(SelectionKey key) throws IOException {
		ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
		// Accept the connection and make it non-blocking
		SocketChannel socketChannel = serverSocketChannel.accept();
		// Socket socket = socketChannel.socket();
		socketChannel.configureBlocking(false);

		// Register the new SocketChannel with our Selector, indicating
		// we'd like to be notified when there's data waiting to be read
		socketChannel.register(this.selector, SelectionKey.OP_READ);

	}

	public static void main(String[] args) {
		try {
			Properties prop = loadProperties();
			final int localport = Integer.parseInt(prop.getProperty("localPort"));
			final int remotePort = Integer.parseInt(prop.getProperty("remotePort"));
			final String remoteHost = prop.getProperty("remoteHost");

			Worker worker =
			                new Worker(
			                           new ConnectingIOReactor(InetAddress.getByName(remoteHost),
			                                                   remotePort));
			new Thread(worker).start();

			new Thread(new ListeningIOReactor(null, localport, worker)).start();
		} catch (IOException e) {
			LOGGER.error("Exception was thrown while settingup the IOReactor", e);
		}
	}

	private static Properties loadProperties() {
		Properties prop = null;
		try {
			prop = new Properties();
			InputStream inputStream = new FileInputStream("src/main/resources/config.properties");
			prop.load(inputStream);

		} catch (FileNotFoundException e) {
			LOGGER.error("Property file could not be loaded properly", e);
		} catch (IOException e) {
			LOGGER.error("IOException was encountered whileloading the property file.", e);
		}

		return prop;
	}

	public void send(SocketChannel socket, byte[] data) {
		synchronized (this.changeRequests) {
			// Indicate we want the interest ops set changed
			this.changeRequests.add(new ChangeRequest(socket, ChangeRequest.CHANGEOPS,
			                                          SelectionKey.OP_WRITE));

			// And queue the data we want written
			synchronized (this.pendingData) {
				List<ByteBuffer> queue = this.pendingData.get(socket);
				if (queue == null) {
					queue = new ArrayList<ByteBuffer>();
					this.pendingData.put(socket, queue);
				}
				queue.add(ByteBuffer.wrap(data));
			}
		}

		// Finally, wake up our selecting thread so it can make the required
		// changes
		this.selector.wakeup();

	}

}
