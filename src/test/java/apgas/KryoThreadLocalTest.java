package apgas;

import static apgas.Constructs.async;
import static apgas.Constructs.finish;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.ArrayList;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import apgas.impl.KryoSerializer;

public class KryoThreadLocalTest implements Serializable {

	/** Serial Version UID */
	private static final long serialVersionUID = 1131111453796155741L;

	private class Obj implements Serializable {
		/** Serial Version UID */
		private static final long serialVersionUID = 8162386968529560900L;
		public int n;
		@SuppressWarnings("unused")
		public String s;
		public ArrayList<String> list;

		public Obj(int n, String s) {
			this.n = n;
			this.s = s;
			list = new ArrayList<String>();
			for (int i = 0; i < n; i++) {
				list.add(s);
			}
		}
	}

	@BeforeClass
	public static void setupBeforClass() {
		System.setProperty(Configuration.APGAS_PLACES, "4");
		// System.setProperty("apgas.serialization", "java");
		GlobalRuntime.getRuntime();
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		GlobalRuntime.getRuntime().shutdown();
	}

	@Test
	public void testReset() throws Throwable {
		final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
		int[] offset = new int[2];
		int[] size = new int[2];

		Obj obj0 = new Obj(100, "a");
		Obj obj1 = new Obj(101, "b");

		for (int i = 0; i < 2; i++) {
			offset[i] = byteOut.size();

			final Output output = new Output(byteOut);
			final Kryo kryo = KryoSerializer.kryoThreadLocal.get();
			kryo.reset();
			kryo.setAutoReset(false);

			kryo.writeClassAndObject(output, obj0);
			kryo.writeClassAndObject(output, obj1);
			kryo.writeClassAndObject(output, obj0);
			output.close();

			size[i] = byteOut.size() - offset[i];
		}

		try {
			finish(() -> {
				async(() -> {
					for (int i = 0; i < 2; i++) {
						final Input in = new Input(byteOut.toByteArray(), offset[i], size[i]);
						final Kryo k = KryoSerializer.kryoThreadLocal.get();
						k.reset();
						k.setAutoReset(false);

						Obj o0 = (Obj) k.readClassAndObject(in);
						@SuppressWarnings("unused")
						Obj o1 = (Obj) k.readClassAndObject(in);
						Obj o2 = (Obj) k.readClassAndObject(in);

						o0.n++;
						assertEquals(o0.n, o2.n);
					}
				});
			});
		} catch (MultipleException me) {
			me.printStackTrace();
			throw me.getSuppressed()[0];
		}

	}
}
