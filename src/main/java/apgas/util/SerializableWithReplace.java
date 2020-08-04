package apgas.util;

import java.io.Serializable;

/**
 * A serializable interface for classes that have {@code writeReplace()}. 
 * The interface is used to tell {@code KryoSerializer} to use its {@code CustomSerializer} when using {@code KryoSerializer}. 
 * <p>
 * No need to use this interface for closures because the KryoSerialzier already knows that closures are using `writeReplace()`.
 */
public interface SerializableWithReplace extends Serializable {

}
