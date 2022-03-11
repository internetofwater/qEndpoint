package com.the_qa_company.q_endpoint.utils.sail;

import org.eclipse.rdf4j.common.concurrent.locks.Lock;
import org.eclipse.rdf4j.common.concurrent.locks.LockManager;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.helpers.SailWrapper;

/**
 * A sail to create multiple Connection input points.
 *
 * To create a new connection, the user should call {@link #startCreatingConnection()}, {@link #getConnection()} and
 * complete it with {@link #completeCreatingConnection()}.
 *
 * <pre>
 * // prepare new connection
 * sail.startCreatingConnection();
 *
 * // get a the connection (can be used how much you want, but at least once to close it)
 * sail.getConnection();
 *
 * // complete the creation
 * sail.completeCreatingConnection();
 * </pre>
 *
 * @author Antoine Willerval
 */
public class MultiInputSail extends SailWrapper {
	private final LockManager lockManager = new LockManager();
	private Lock lock;
	private SailConnection lastConnection;

	/**
	 * Creates a new MultiInputSail object that wraps the supplied connection.
	 *
	 * @param wrappedSail the sail to allow multiple sail
	 */
	public MultiInputSail(Sail wrappedSail) {
		super(wrappedSail);
	}

	/**
	 * start a new connection creation, the next call to {@link #getConnection()} will return the same connection.
	 * @throws SailException if the creating is interrupted
	 */
	public synchronized void startCreatingConnection() throws SailException {
		try {
			lockManager.waitForActiveLocks();
		} catch (InterruptedException e) {
			throw new SailException("Interruption while waiting for active locks", e);
		}

		lock = lockManager.createLock("");

		lastConnection = getBaseSail().getConnection();
	}

	@Override
	public synchronized SailConnection getConnection() throws SailException {
		checkCreatingConnectionStarted();
		return lastConnection;
	}

	/**
	 * complete a new connection creation.
	 * @throws SailException if {@link #startCreatingConnection()} wasn't called before
	 */
	public synchronized void completeCreatingConnection() throws SailException {
		checkCreatingConnectionStarted();
		lock.release();
		lastConnection = null;
	}

	public void checkCreatingConnectionStarted() throws SailException {
		if (lastConnection == null) {
			throw new SailException("The MultiInputSail wasn't started!");
		}
	}
}
