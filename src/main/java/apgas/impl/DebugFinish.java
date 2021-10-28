package apgas.impl;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import apgas.Constructs;
import apgas.Place;
import apgas.SerializableJob;
import apgas.util.GlobalID;

/**
 * A Finish implementation which keeps the exceptions thrown inside a finish
 * accessible through some specific features of this class which makes it
 * possible to access thrown errors even after a Finish has completed, or in the
 * event of a test timeout due to a silent error.
 * <p>
 * The implementation is largely identical to that of class
 * {@link DefaultFinish}. Only the handling of exceptions thrown during
 * activities differs.
 *
 * @author Patrick Finnerty
 *
 */
@SuppressWarnings("javadoc")
public final class DebugFinish implements Serializable, Finish {

	/**
	 * Factory for {@link DebugFinish} instances.
	 *
	 * @author Patrick Finnerty
	 *
	 */
	static class Factory extends Finish.Factory {
		@Override
		DebugFinish make(Finish parent) {
			return new DebugFinish();
		}
	}

	/**
	 * Serial Version UID
	 */
	private static final long serialVersionUID = -6686848418378907597L;

	private static final Field suppressedExceptions_Field;

	private static final Object throwableSentinel;

	/**
	 * Local map in which the throwables thrown by local activities are collected.
	 * The {@link Long} key corresponds to the {@link GlobalID#gid()} value returned
	 * by the global id held by the finish under which the throwables were thrown.
	 * 
	 * @see #dumpAllSuppressedExceptions()
	 */
	private static final Map<DebugFinish, List<Throwable>> localThrowables = new ConcurrentHashMap<>();

	static {
		Field suppressedExceptions = null;
		Object emptyThrowableList = null;
		try {
			suppressedExceptions = Throwable.class.getDeclaredField("suppressedExceptions");
			final Field suppressedSentinel = Throwable.class.getDeclaredField("SUPPRESSED_SENTINEL");
			suppressedExceptions.setAccessible(true);
			suppressedSentinel.setAccessible(true);
			emptyThrowableList = suppressedSentinel.get(null);
		} catch (IllegalAccessException | IllegalArgumentException | NoSuchFieldException | SecurityException e) {
			e.printStackTrace();
		}
		suppressedExceptions_Field = suppressedExceptions;
		throwableSentinel = emptyThrowableList;
	}

	/**
	 * Method specific to {@link DebugFinish} which prints the suppressed exceptions
	 * that occurred on every place.
	 */
	public static void dumpAllSuppressedExceptions() {
		for (Place p : Constructs.places()) {
			Constructs.at(p, () -> dumpAllLocalSuppressedExceptions());
		}
	}

	/**
	 * Method which indicates if any of the Places have some suppressed exceptions
	 * which have not be dumped yet with {@link #dumpAllSuppressedExceptions()}.
	 * 
	 * @return true if there are some exceptions kept in record which have not been
	 *         dumped yet
	 */
	public static boolean suppressedExceptionsPresent() {
		for (Place p : Constructs.places()) {
			if (!Constructs.at(p, () -> localThrowables.isEmpty())) {
				return true;
			}
		}
		return false;
	}

	private static void dumpAllLocalSuppressedExceptions() {
		System.err.println("Suppressed Exceptions on place(" + GlobalRuntimeImpl.getRuntime().here + "):");
		for (DebugFinish key : localThrowables.keySet()) {

			List<Throwable> throwableList = localThrowables.remove(key);
			System.err.println(key + " contained " + throwableList.size() + " throwables");
			for (Throwable t : throwableList) {
				t.printStackTrace(System.err);
			}
		}
		System.err.flush();
	}

	/**
	 * Recursively check and fixes the suppressed exceptions of the given Throwable.
	 *
	 * @param t throwable to check
	 */
	private static void fixSuppressedExceptions(Throwable t) {
		try {
			for (final Throwable st : t.getSuppressed()) {// getSuppressed may throw NPE
				fixSuppressedExceptions(st); // recursively make the check
			}
		} catch (final NullPointerException e) {
			// The throwable "t"'s suppressed exceptions member needs to be fixed
			try {
				suppressedExceptions_Field.set(t, throwableSentinel);
			} catch (IllegalArgumentException | IllegalAccessException e1) {
				e1.printStackTrace();
			}
		}
		if (t.getCause() != null) {
			fixSuppressedExceptions(t.getCause());
		}
	}

	/**
	 * A multi-purpose task counter.
	 * <p>
	 * This counter counts:
	 * <ul>
	 * <li>all tasks for a local finish</li>
	 * <li>places with non-zero task counts for a root finish</li>
	 * <li>local task count for a remote finish</li>
	 * </ul>
	 */
	private transient int count;

	/**
	 * Per-place count of task spawned minus count of terminated tasks.
	 * <p>
	 * Null until a remote task is spawned.
	 */
	private transient int counts[];
	/**
	 * Uncaught exceptions collected by this finish construct.
	 */
	private transient List<Throwable> exceptions;

	/**
	 * The {@link GlobalID} instance for this finish construct.
	 * <p>
	 * Null until the finish object is first serialized.
	 */
	GlobalID id;

	/**
	 * Constructs a finish instance.
	 */
	DebugFinish() {
		final int here = GlobalRuntimeImpl.getRuntime().here;
		spawn(here);
	}

	@Override
	public synchronized void addSuppressed(Throwable exception) {
		// Specific to DebugFinish:
		// Add the exception to the list of local exceptions
		List<Throwable> localThrowablesForThisFinish = localThrowables.computeIfAbsent(this, k -> {
			return Collections.synchronizedList(new LinkedList<Throwable>());
		});
		localThrowablesForThisFinish.add(exception);

		final int here = GlobalRuntimeImpl.getRuntime().here;
		if (id == null || id.home.id == here) {

			// root finish
			if (exceptions == null) {
				exceptions = new ArrayList<>();
			}

			exceptions.add(exception);
		} else {
			// remote finish: spawn remote task to transfer exception to root finish
			final SerializableThrowable t = new SerializableThrowable(exception);
			final DebugFinish that = this;
			spawn(id.home.id);
			new Task(this, (SerializableJob) () -> {
				that.addRemoteSuppressed(t.t);
				fixSuppressedExceptions(t.t);
			}, here).asyncAt(id.home.id);
		}
	}

	public synchronized void addRemoteSuppressed(Throwable t) {
		if (exceptions == null) {
			exceptions = new ArrayList<>();
		}

		exceptions.add(t);
	}

	@Override
	public synchronized boolean block() {
		while (count != 0) {
			try {
				wait();
			} catch (final InterruptedException e) {
			}
		}
		return count == 0;
	}

	@Override
	public synchronized List<Throwable> exceptions() {
		return exceptions;
	}

	@Override
	public synchronized boolean isReleasable() {
		return count == 0;
	}

	/**
	 * Deserializes the finish object.
	 *
	 * @return the finish object
	 */
	public Object readResolve() {
		// count = 0;
		DebugFinish me = (DebugFinish) id.putHereIfAbsent(this);
		if (me == null) {
			me = this;
		}
		synchronized (me) {
			final int here = GlobalRuntimeImpl.getRuntime().here;
			if (id.home.id != here && me.counts == null) {
				me.counts = new int[GlobalRuntimeImpl.getRuntime().maxPlace()];
			}
			return me;
		}
	}

	/**
	 * Reallocates the {@link #counts} array to account for larger place counts.
	 *
	 * @param min a minimal size for the reallocation
	 */
	private void resize(int min) {
		final int[] tmp = new int[Math.max(min, GlobalRuntimeImpl.getRuntime().maxPlace())];
		System.arraycopy(counts, 0, tmp, 0, counts.length);
		counts = tmp;
	}

	@Override
	public synchronized void spawn(int p) {
		final int here = GlobalRuntimeImpl.getRuntime().here;
		if (id == null || id.home.id == here) {
			// local or root finish
			if (counts == null) {
				if (here == p) {
					count++;
					return;
				}
				counts = new int[GlobalRuntimeImpl.getRuntime().maxPlace()];
				counts[here] = count;
				count = 1;
			}
			if (p >= counts.length) {
				resize(p + 1);
			}
			if (counts[p]++ == 0) {
				count++;
			}
			if (counts[p] == 0) {
				--count;
			}
		} else {
			// remote finish
			if (p >= counts.length) {
				resize(p + 1);
			}
			counts[p]++;
		}
	}

	@Override
	public synchronized void submit(int p) {
		final int here = GlobalRuntimeImpl.getRuntime().here;
		if (id != null && id.home.id != here) {
			// remote finish
			count++;
		}
	}

	@Override
	public synchronized void tell() {
		final int here = GlobalRuntimeImpl.getRuntime().here;
		if (id == null || id.home.id == here) {
			// local or root finish
			if (counts != null) { // If root finish with remote asyncs
				if (counts[here] == 0) {
					count++; // Prevents the finish from progressing in the edge case where the task was sent
					// from a remote place
				}
				if (--counts[here] != 0) { // Decrement here.
					return;
				}
			}
			if (--count == 0) {
				notifyAll();
			}
		} else {
			// remote finish
			--counts[here];
			if (--count == 0) {
				final int _counts[] = counts;
				final DebugFinish that = this;
				GlobalRuntimeImpl.getRuntime().transport.send(id.home.id, () -> that.update(_counts));
				Arrays.fill(counts, 0);
			}
		}
	}

	@Override
	public synchronized void unspawn(int p) {
		final int here = GlobalRuntimeImpl.getRuntime().here;
		if (id == null || id.home.id == here) {
			// root finish
			if (counts == null) {
				// task must have been local
				--count;
			} else {
				if (counts[p] == 0) {
					count++;
				}
				if (--counts[p] == 0) {
					--count;
				}
			}
		} else {
			// remote finish
			--counts[p];
		}
	}

	/**
	 * Applies an update message from a remote finish to the root finish.
	 *
	 * @param _counts incoming counters
	 */
	synchronized void update(int _counts[]) {
		if (_counts.length > counts.length) {
			resize(_counts.length);
		}
		for (int i = 0; i < _counts.length; i++) {
			if (counts[i] != 0) {
				--count;
			}
			counts[i] += _counts[i];
			if (counts[i] != 0) {
				count++;
			}
		}
		if (count == 0) {
			notifyAll();
		}
	}

	/**
	 * Prepares the finish object for serialization.
	 *
	 * @return this
	 */
	public synchronized Object writeReplace() {
		if (id == null) {
			id = new GlobalID();
			id.putHere(this);
		}
		return this;
	}

	/**
	 * 
	 */
	@Override
	public String toString() {
		if (id == null) {
			return "Local Finish:" + "@" + Integer.toHexString(hashCode());
		} else {
			return "Global Finish:gid" + id.gid();
		}
	}
}
