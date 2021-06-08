package apgas.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;

import org.objenesis.instantiator.ObjectInstantiator;
import org.objenesis.strategy.InstantiatorStrategy;
import org.objenesis.strategy.SerializingInstantiatorStrategy;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.io.UnsafeInput;
import com.esotericsoftware.kryo.io.UnsafeOutput;
import com.esotericsoftware.kryo.serializers.ClosureSerializer;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.StreamSerializer;

import apgas.Place;
import apgas.util.GlobalID;
import apgas.util.PlaceLocalObject;
import apgas.util.SerializableWithReplace;

/**
 * The {@link KryoSerializer} implements serialization using Kryo.
 *
 */
public class KryoSerializer implements StreamSerializer<Object> {

	@SuppressWarnings("rawtypes")
	private static HashMap<Class, Serializer> additionalRegistrations = new HashMap<>();

	@SuppressWarnings("rawtypes")
	public static void registerClass(Class clazz, Serializer serializer) {
		additionalRegistrations.put(clazz, serializer);
	}

	private static InstantiatorStrategy instantiatorStrategy = new DefaultForColInstantiatorStrategy();

	public static void setInstantiatorStrategy(InstantiatorStrategy strategy) {
		instantiatorStrategy = strategy;
	}

	public static final ThreadLocal<Kryo> kryoThreadLocal = new ThreadLocal<Kryo>() {
		@Override
		protected Kryo initialValue() {
			return getKryoInstance();
		}
	};

	public static final Kryo getKryoInstance() {
		final Kryo kryo = new Kryo() {
			@Override
			@SuppressWarnings({ "rawtypes", "unchecked" })
			protected Serializer newDefaultSerializer(Class type) {
				try {
					type.getMethod("writeReplace");
					return new CustomSerializer();
				} catch (final NoSuchMethodException e) {
				}
				return super.newDefaultSerializer(type);
			}
		};
		kryo.addDefaultSerializer(DefaultFinish.class, new DefaultFinishSerializer());
		kryo.addDefaultSerializer(DebugFinish.class, new DebugFinishSerializer());
		kryo.addDefaultSerializer(SerializableWithReplace.class, new CustomSerializer());
		kryo.setInstantiatorStrategy(instantiatorStrategy);
		kryo.register(Task.class);
		kryo.register(UncountedTask.class);
		kryo.register(Place.class);
		kryo.register(GlobalID.class);
		kryo.register(java.lang.invoke.SerializedLambda.class);
		kryo.register(ClosureSerializer.Closure.class, new ClosureSerializer2());
		try {
			kryo.register(Class.forName(PlaceLocalObject.class.getName() + "$ObjectReference"));
		} catch (final ClassNotFoundException e) {
		}
		additionalRegistrations.forEach(
				(@SuppressWarnings("rawtypes") Class clazz, @SuppressWarnings("rawtypes") Serializer serializer) -> {
					if (serializer == null) {
						kryo.register(clazz);
					} else {
						kryo.register(clazz, serializer);
					}
				});
		return kryo;
	}

	@Override
	public int getTypeId() {
		return 42;
	}

	@Override
	public void write(ObjectDataOutput objectDataOutput, Object object) throws IOException {
		final Output output = new UnsafeOutput((OutputStream) objectDataOutput);
		final Kryo kryo = kryoThreadLocal.get();
		kryo.writeClassAndObject(output, object);
		output.flush();
	}

	@Override
	public Object read(ObjectDataInput objectDataInput) throws IOException {
		final Input input = new UnsafeInput((InputStream) objectDataInput);
		final Kryo kryo = kryoThreadLocal.get();
		return kryo.readClassAndObject(input);
	}

	@Override
	public void destroy() {
	}

	private static class CustomSerializer extends Serializer<Object> {
		@Override
		public void write(Kryo kryo, Output output, Object object) {
			try {
				final Method writeReplace = object.getClass().getMethod("writeReplace");
				object = writeReplace.invoke(object);
			} catch (final Exception e) {
			}
			kryo.writeClassAndObject(output, object);
		}

		@Override
		public Object read(Kryo kryo, Input input, Class<Object> type) {
			Object object = kryo.readClassAndObject(input);
			try {
				final Method readResolve = object.getClass().getDeclaredMethod("readResolve");
				readResolve.setAccessible(true);
				object = readResolve.invoke(object);
			} catch (final Exception e) {
			}
			return object;
		}
	}

	private static class DefaultFinishSerializer extends Serializer<DefaultFinish> {
		@Override
		public void write(Kryo kryo, Output output, DefaultFinish object) {
			object.writeReplace();
			kryo.writeObject(output, object.id);
		}

		@Override
		public DefaultFinish read(Kryo kryo, Input input, Class<DefaultFinish> type) {
			final DefaultFinish f = kryo.newInstance(type);
			f.id = kryo.readObject(input, GlobalID.class);
			return (DefaultFinish) f.readResolve();
		}
	}

	private static class DebugFinishSerializer extends Serializer<DebugFinish> {

		@Override
		public void write(Kryo kryo, Output output, DebugFinish object) {
			object.writeReplace();
			kryo.writeObject(output, object.id);
		}

		@Override
		public DebugFinish read(Kryo kryo, Input input, Class<DebugFinish> type) {
			final DebugFinish f = kryo.newInstance(type);
			f.id = kryo.readObject(input, GlobalID.class);
			return (DebugFinish) f.readResolve();
		}
	}

	public static class DefaultForColInstantiatorStrategy implements InstantiatorStrategy {
		private Kryo.DefaultInstantiatorStrategy forCols = new Kryo.DefaultInstantiatorStrategy();
		private SerializingInstantiatorStrategy ser = new SerializingInstantiatorStrategy();

		public DefaultForColInstantiatorStrategy() {
			forCols.setFallbackInstantiatorStrategy(ser);
		}

		@SuppressWarnings("unchecked")
		public <T> ObjectInstantiator<T> newInstantiatorOf(Class<T> type) {
			if (SerializableWithReplace.class.isAssignableFrom(type)) {
				return ser.newInstantiatorOf(type);
			} else if (java.util.Collection.class.isAssignableFrom(type)
					|| java.util.Map.class.isAssignableFrom(type)) {
				return forCols.newInstantiatorOf(type);
			} else {
				return ser.newInstantiatorOf(type);
			}
		}
	}

}
