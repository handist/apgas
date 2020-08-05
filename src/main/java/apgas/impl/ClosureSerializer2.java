package apgas.impl;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.ClosureSerializer;

public class ClosureSerializer2 extends ClosureSerializer {
    public ClosureSerializer2 () {
	super();
    }
    
    public void write (Kryo kryo, Output output, Object object) {
	try {
	    super.write(kryo, output, object);
	} catch (RuntimeException e) {
	    if(e.getCause() == null) throw new KryoException("Could not serialize lambda");
	    if(e.getCause() instanceof KryoException) throw (KryoException)e.getCause();
	    else throw new KryoException("Could not serialize lambda", e.getCause());
	}
    }
    public Object read (Kryo kryo, Input input, Class type) {
	try {
	    return super.read(kryo, input, type);
	} catch (RuntimeException e) {
	    if(e.getCause() == null) throw new KryoException("Could not deserialize lambda");
	    if(e.getCause() instanceof KryoException) throw (KryoException)e.getCause();
	    else throw new KryoException("Could not deserialize lambda", e.getCause());
	}
    }

    public Object copy (Kryo kryo, Object original) {
	try {
	    return super.copy(kryo, original);
	} catch (RuntimeException e) {
	    if(e.getCause() == null) throw new KryoException("Could not serialize lambda (resolve)");
	    if(e.getCause() instanceof KryoException) throw (KryoException)e.getCause();
	    else throw new KryoException("Could not serialize lambda (resolve)", e.getCause());
	}
    }
}

