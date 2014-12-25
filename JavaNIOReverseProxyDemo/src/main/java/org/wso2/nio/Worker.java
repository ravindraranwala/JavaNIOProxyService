package org.wso2.nio;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;

import org.apache.log4j.Logger;

/**
 * Worker thread which processes client requests at the Listening ioReactor
 * level.
 * 
 * @author ravindra
 *
 */
public class Worker implements Runnable {
	private static final Logger LOGGER = Logger.getLogger(Worker.class);
	private List<ServerDataEvent> queue = new LinkedList<ServerDataEvent>();

	private final ConnectingIOReactor client;

	public Worker(ConnectingIOReactor connectingIOReactor) {
		this.client = connectingIOReactor;

		Thread t = new Thread(client);
		t.setDaemon(true);
		t.start();
	}

	public void processData(ListeningIOReactor server, SocketChannel socket, byte[] data, int count) {
		LOGGER.info("Request Processing ...");
		byte[] dataCopy = new byte[count];
		System.arraycopy(data, 0, dataCopy, 0, count);
		synchronized (queue) {
			queue.add(new ServerDataEvent(server, socket, dataCopy));
			queue.notify();
		}
	}

	public void run() {
		ServerDataEvent dataEvent;

		while (true) {
			// Wait for data to become available
			synchronized (queue) {
				while (queue.isEmpty()) {
					try {
						queue.wait();
					} catch (InterruptedException e) {
					}
				}
				dataEvent = (ServerDataEvent) queue.remove(0);
			}

			// Return to sender
			// LOGGER.info("Returning the response back to the client.");
			// dataEvent.server.send(dataEvent.socket, dataEvent.data);

			// Send the request data to the connecting ioReactor.
			sendRequestToBackend(dataEvent.data, dataEvent.socket, dataEvent.server);
		}

	}

	private void sendRequestToBackend(final byte[] data, final SocketChannel socket,
	                                  final ListeningIOReactor listeningIOReactor) {

		try {
			LOGGER.info("Sending the request to the Connecting side of the Proxy service.");

			RspHandler handler = new RspHandler(socket, listeningIOReactor);
			client.send(data, handler);
			handler.waitForResponse();
		} catch (UnknownHostException e) {
			LOGGER.error("Unknown Host while sending the request to the backend", e);
		} catch (IOException e) {
			LOGGER.error("IOException was thrown while sending the request to the backend", e);
		}

	}

}
