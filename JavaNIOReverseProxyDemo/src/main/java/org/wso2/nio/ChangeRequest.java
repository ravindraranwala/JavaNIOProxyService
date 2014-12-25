package org.wso2.nio;

import java.nio.channels.SocketChannel;

/**
 * Used to change our interest in connection events on a given
 * {@link SocketChannel}
 * 
 * @author ravindra
 *
 */
public class ChangeRequest {
	public static final int REGISTER = 1;
	public static final int CHANGEOPS = 2;

	public SocketChannel socket;
	public int type;
	public int ops;

	public ChangeRequest(SocketChannel socket, int type, int ops) {
		this.socket = socket;
		this.type = type;
		this.ops = ops;
	}

}
