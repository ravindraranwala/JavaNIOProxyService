package org.wso2.nio;

import java.nio.channels.SocketChannel;

/**
 * Represents a Client request arrives at the Listening IOReactor level.
 * 
 * @author ravindra
 *
 */
public class ServerDataEvent {
	public ListeningIOReactor server;
	public SocketChannel socket;
	public byte[] data;

	public ServerDataEvent(ListeningIOReactor server, SocketChannel socket, byte[] data) {
		this.server = server;
		this.socket = socket;
		this.data = data;
	}

}
