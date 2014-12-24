package org.wso2.nio;

import java.nio.channels.SocketChannel;

import org.apache.log4j.Logger;

/**
 * Responsible for handling response received from the backend and deliver it
 * upto the listening ioReactor level.
 * 
 * @author ravindra
 *
 */
public class RspHandler {
	private final static Logger LOGGER = Logger.getLogger(RspHandler.class);

	private final SocketChannel responseChannel;
	private final ListeningIOReactor listeningIOReactor;
	private byte[] rsp = null;

	public RspHandler(SocketChannel responseChannel, ListeningIOReactor listeningIOReactor) {
		this.responseChannel = responseChannel;
		this.listeningIOReactor = listeningIOReactor;
	}

	public synchronized boolean handleResponse(byte[] rsp) {
		this.rsp = rsp;
		this.notify();
		return true;
	}

	public synchronized void waitForResponse() {
		while (this.rsp == null) {
			try {
				this.wait();
			} catch (InterruptedException e) {
			}
		}

		System.out.println(new String(this.rsp));
		// Sending the response back to the caller.

		LOGGER.info("Writing the response back to the client.");
		listeningIOReactor.send(responseChannel, rsp);

		// listeningIOReactor.send(responseChannel, rsp);

	}
}
