package apgas;

import static apgas.Constructs.async;
import static apgas.Constructs.asyncAt;
import static apgas.Constructs.at;
import static apgas.Constructs.finish;
import static apgas.Constructs.here;
import static apgas.Constructs.place;
import static apgas.Constructs.places;
import static apgas.ExtendedConstructs.asyncArbitraryFinish;
import static apgas.ExtendedConstructs.asyncAtWithCoFinish;
import static apgas.ExtendedConstructs.asyncDifferentFinish;
import static apgas.ExtendedConstructs.currentFinish;
import static org.junit.Assert.assertEquals;

import java.io.Serializable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import apgas.impl.Finish;
import apgas.util.PlaceLocalArray;
import apgas.util.PlaceLocalObject;

/**
 * Tests for the {@link ExtendedConstructs} Unfortunately, testing these
 * features proves challenging and the quality of the tests present in this
 * class leaves much to be desired.
 * 
 * @author Patrick Finnerty
 *
 */
public class ExtendedConstructsTest implements Serializable {

	/** Managed blocker used to synchronize the threads */
	private static class Lock extends PlaceLocalObject implements ManagedBlocker {
		final Semaphore lock;
		volatile boolean releasable;

		public Lock() {
			lock = new Semaphore(0);
			releasable = false;
		}

		@Override
		public boolean block() {
			try {
				lock.acquire();
				releasable = true;
			} catch (final InterruptedException e) {
				e.printStackTrace();
			}
			return releasable;
		}

		@Override
		public boolean isReleasable() {
			return releasable || (releasable = lock.tryAcquire());
		}

		public void unblock() {
			releasable = true;
			lock.release();
		}

	}

	/** Serial Version UID */
	private static final long serialVersionUID = -3497063493183516285L;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		System.setProperty(Configuration.APGAS_PLACES, "2");
		// System.setProperty("apgas.serialization", "java");
		GlobalRuntime.getRuntime();
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		GlobalRuntime.getRuntime().shutdown();
	}

	/** Counter used to check that asynchronous tasks have completed */
	AtomicInteger counter;
	/** Used to keep the local handle of a finish */
	transient volatile Finish localFinishHandle;

	/**
	 * Test that checks that the arbitrary finish construct works as intended in a
	 * situation where making an asyncAt would be equivalent
	 * 
	 * @throws Throwable if thrown during the test
	 */
	@Test(timeout = 5000)
	public void testArbitraryFinish() throws Throwable {
		finish(() -> {
			asyncAt(place(1), () -> {
				Finish f = currentFinish();
				asyncArbitraryFinish(place(0), () -> {
					System.out.println("Message from asyncArbitraryFinish construct");
				}, f);
			});
		});
	}

	/**
	 * Checks that the extended constructs with the extra finish assignments work
	 * <p>
	 * This test is currently Ignored because of issues with the scheduling of
	 * tasks. Depending on the order in which tasks are scheduled, this test rarely
	 * runs successfully.
	 * 
	 * @throws Throwable if thrown during execution
	 */
	@Ignore
	@Test(timeout = 20000)
	public void testCoFinish() throws Throwable {
		final Lock distributedLock1 = Lock.make(places(), ExtendedConstructsTest.Lock::new);

		try {
			// Enclosing finish
			finish(() -> {
				// First finish used as co-finish
				async(() -> {
					finish(() -> {
						for (Place p : places()) {
							try {

								asyncAt(p, () -> {
									System.out.println("Going to block right here on " + here());
									z_initFinishMemberAndBlock(distributedLock1);
								});
							} catch (Throwable e) {
								e.printStackTrace();
								throw e;
							}
						}
					});
					// Cannot progress in this async until all co-finish parts have also completed
					assertEquals(places().size(), counter.get());
				});

				// Second finish with co-finish to the first one
				finish(() -> {
					for (Place p : places()) {
						System.out.println("Sending to unblock progress on " + p);
						asyncAtWithCoFinish(p, () -> {
							try {
								System.out.println("Going to unblock progress on " + here());
								distributedLock1.unblock();
								System.out.println("Progress on " + here() + " possible");
								while (localFinishHandle == null)
									;
								asyncDifferentFinish(localFinishHandle, () -> z_incrementPlaceZeroCounter());
							} catch (Throwable e) {
								e.printStackTrace();
							}
						}, localFinishHandle);
					}
				});
			});
		} catch (MultipleException me) {
			me.printStackTrace();
			throw me.getSuppressed()[0];
		}
	}

	/**
	 * Tests the async with co-finish when no co-finish are used. Behavior should be
	 * identical to using a normal async
	 * 
	 * @throws Throwable if thrown during the test
	 */
	@Test(timeout = 5000)
	public void testWithNoCoFinish() throws Throwable {
		final PlaceLocalArray<Place> pla = PlaceLocalArray.make(places(), 1);
		try {
			finish(() -> {
				for (final Place p : places()) {
					asyncAtWithCoFinish(p, () -> {
						pla.set(0, here());
					});
				}
			});
		} catch (MultipleException me) {
			me.printStackTrace();
			throw me.getSuppressed()[0];
		}
		for (final Place p : places()) {
			assertEquals(at(p, () -> pla.get(0)), p);
		}
	}

	private void z_incrementPlaceZeroCounter() {
		asyncAt(place(0), () -> {
			counter.incrementAndGet();
		});
	}

	private void z_initFinishMemberAndBlock(ManagedBlocker blocker) {
		localFinishHandle = currentFinish();
		try {
			ForkJoinPool.managedBlock(blocker);
		} catch (InterruptedException e) {
		}
	}
}
