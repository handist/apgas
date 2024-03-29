/*
 *  This file is part of the X10 project (http://x10-lang.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  (C) Copyright IBM Corporation 2006-2016.
 */

package apgas;

import static apgas.Constructs.async;
import static apgas.Constructs.asyncAt;
import static apgas.Constructs.at;
import static apgas.Constructs.finish;
import static apgas.Constructs.here;
import static apgas.Constructs.place;
import static apgas.Constructs.places;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import apgas.impl.Config;
import apgas.impl.DebugFinish;
import apgas.util.GlobalRef;
import apgas.util.PlaceLocalArray;

@SuppressWarnings("javadoc")
public class ApgasTest {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		System.setProperty(Configuration.APGAS_PLACES, "4");
		// System.setProperty("apgas.serialization", "java");
		System.setProperty(Config.APGAS_FINISH, DebugFinish.class.getCanonicalName());
		GlobalRuntime.getRuntime();
	}

	@Rule
	public TestName nameOfCurrentTest = new TestName();

	@After
	public void afterEachTest() {
		if (DebugFinish.class.getCanonicalName().equals(System.getProperty(Config.APGAS_FINISH))) {
			if (DebugFinish.suppressedExceptionsPresent()) {
				System.err.println("Dumping the errors that occurred during " + nameOfCurrentTest.getMethodName());
				System.err.flush();
				// If we are using the DebugFinish, dump all throwables collected on each host
				DebugFinish.dumpAllSuppressedExceptions();
			}
		}
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		GlobalRuntime.getRuntime().shutdown();
	}

	@Test
	public void testHere() {
		assertEquals(here(), place(0));
		assertEquals(here(), new Place(0));
	}

	@Test
	public void testPlaces() {
		assertEquals(places().size(), 4);
		for (int i = 0; i < 4; i++) {
			assertEquals(place(i).id, i);
		}
	}

	@Test
	public void testAsyncFinish() {
		final int a[] = new int[1];
		finish(() -> async(() -> a[0] = 42));
		assertEquals(a[0], 42);
	}

	@Test
	public void testGlobalRef() {
		final int a[] = new int[1];
		final GlobalRef<int[]> _a = new GlobalRef<>(a);
		finish(() -> asyncAt(place(1), () -> asyncAt(_a.home(), () -> _a.get()[0] = 42)));
		assertEquals(a[0], 42);
		_a.free();
	}

	@Test
	public void testPlaceLocalHandle() {
		final GlobalRef<Place> plh = new GlobalRef<>(places(), () -> here());
		for (final Place p : places()) {
			assertEquals(at(p, () -> plh.get()), p);
		}
		plh.free();
	}

	@Test
	public void testPlaceLocalArray() {
		final PlaceLocalArray<Place> pla = PlaceLocalArray.make(places(), 1);
		finish(() -> {
			for (final Place p : places()) {
				asyncAt(p, () -> pla.set(0, here()));
			}
		});
		for (final Place p : places()) {
			assertEquals(at(p, () -> pla.get(0)), p);
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void testIllegalArgumentException() {
		place(-1);
	}

	@Test(expected = DeadPlaceException.class)
	public void testDeadPlaceExceptionAsyncAt() {
		asyncAt(new Place(places().size()), () -> {
		});
	}

	@Test(expected = MultipleException.class)
	public void testMultipleException() {
		finish(() -> {
			throw new RuntimeException();
		});
	}

	@Test(expected = MultipleException.class)
	public void testMultipleExceptionAsync() {
		finish(() -> async(() -> {
			throw new RuntimeException();
		}));
	}

	@Test(expected = MultipleException.class)
	public void testMultipleExceptionAsyncAt() {
		finish(() -> asyncAt(place(1), () -> {
			throw new RuntimeException();
		}));
	}

	public static int fib(int n) {
		if (n < 2) {
			return n;
		}
		final int a[] = new int[2];
		finish(() -> {
			async(() -> a[0] = fib(n - 2));
			a[1] = fib(n - 1);
		});
		return a[0] + a[1];
	}

	@Test
	public void testFib() {
		assertEquals(fib(10), 55);
	}

	@Test(expected = RuntimeException.class)
	public void testSerializationException() throws Throwable {
		if (!"java".equals(System.getProperty("apgas.serialization"))) {
			throw new RuntimeException(); // TODO
		}
		try {
			final Object obj = new Object();
			asyncAt(place(1), () -> obj.toString());
		} catch (final MultipleException e) {
			assertEquals(e.getSuppressed().length, 1);
			throw e.getSuppressed()[0];
		}
	}

	static class Foo implements java.io.Serializable {
		private static final long serialVersionUID = -3520177294998943335L;

		private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
			throw new NotSerializableException(this.getClass().getCanonicalName());
		}
	}

	@Test(expected = NotSerializableException.class)
	public void testDeserializationException() throws Throwable {
		if (!"java".equals(System.getProperty("apgas.serialization"))) {
			throw new NotSerializableException(); // TODO
		}
		final Object obj = new Foo();
		try {
			finish(() -> asyncAt(place(1), () -> obj.toString()));
		} catch (final MultipleException e) {
			assertEquals(e.getSuppressed().length, 1);
			throw e.getSuppressed()[0];
		}
	}

	static class FooException extends RuntimeException {
		private static final long serialVersionUID = 2990207615401829317L;

		final Object obj = new Object();
	}

	@Test(expected = NotSerializableException.class)
	public void testNotSerializableException() throws Throwable {
		if (!"java".equals(System.getProperty("apgas.serialization"))) {
			throw new NotSerializableException(); // TODO
		}
		try {
			finish(() -> asyncAt(place(1), () -> {
				throw new FooException();
			}));
		} catch (final MultipleException e) {
			assertEquals(e.getSuppressed().length, 1);
			throw e.getSuppressed()[0];
		}
	}
}
