package org.red5.server.net.rtmp;

import java.util.Collection;
import java.util.concurrent.CopyOnWriteArraySet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.red5.server.BaseConnection;

public class RTMPClientConnManager implements IRTMPConnManager {

private static final Logger log = LoggerFactory.getLogger(RTMPClientConnManager.class);
	
	private static final RTMPClientConnManager instance = new RTMPClientConnManager();

	protected static CopyOnWriteArraySet<RTMPConnection> rtmpConnections = new CopyOnWriteArraySet<RTMPConnection>();
	
	private RTMPClientConnManager() {
	}
	
	public static RTMPClientConnManager getInstance() {
		return instance;
	}

	/**
	 * Returns a connection matching the given client id.
	 * 
	 * @param clientId
	 * @return connection
	 */
	public RTMPConnection getConnection(int clientId) {
		log.debug("Returning map entry for client id: {}", clientId);
		RTMPConnection connReturn = null;
		for (RTMPConnection conn : rtmpConnections) {
			if (conn.getId() == clientId) {
				connReturn = conn;
				break;
			}
		}
		return connReturn;
	}

	/**
	 * Removes a connection matching the client id specified. If found, the connection
	 * will be returned.
	 * 
	 * @param clientId
	 * @return connection
	 */
	public RTMPConnection removeConnection(int clientId) {
		RTMPConnection connReturn = null;
		for (RTMPConnection conn : rtmpConnections) {
			if (conn.getId() == clientId) {
				connReturn = conn;
				break;
			}
		}
		if (connReturn != null) {
			rtmpConnections.remove(connReturn);
		}
		return connReturn;
	}

	/**
	 * Removes all the connections from the set.
	 */
	public Collection<RTMPConnection> removeConnections() {
		rtmpConnections.clear();
		return null;
	}

	public RTMPConnection createConnection(Class<?> connCls) {
		log.debug("Creating connection, class: {}", connCls.getName());
		if (!RTMPConnection.class.isAssignableFrom(connCls)) {
			throw new IllegalArgumentException("Class was not assignable");
		}
		try {
			RTMPConnection conn = (RTMPConnection) connCls.newInstance();
			// the id may become confused with the Object/String client id
			conn.setId(BaseConnection.getNextClientId());
			log.debug("Connection id set {}", conn.getId());
			rtmpConnections.add(conn);
			log.debug("Connection added to the map");
			return conn;
		} catch (Exception e) {
			log.error("RTMPConnection creation failed", e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public RTMPConnection getConnectionBySessionId(String sessionId) {
		 
		return null;
	}
}
