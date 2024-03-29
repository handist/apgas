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

package apgas.util;

import static apgas.Constructs.async;
import static apgas.Constructs.asyncAt;
import static apgas.Constructs.finish;
import static apgas.Constructs.here;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import apgas.DeadPlaceException;
import apgas.Place;

/**
 * The {@link GlobalID} class provides globally unique IDs and mechanisms to
 * attach place-specific data to these IDs.
 */
public class GlobalID implements Serializable {
	private static final long serialVersionUID = 5480936903198352190L;

	private static final Object NULL = new Object();

	/**
	 * Internal counter.
	 */
	protected static final AtomicInteger count = new AtomicInteger();

	/**
	 * Internal map.
	 */
	protected static final Map<GlobalID, Object> map = new ConcurrentHashMap<>();

	/**
	 * The {@link Place} where this {@link GlobalID} was instantiated.
	 */
	public final Place home;

	/**
	 * The local ID component of this {@link GlobalID} instance.
	 * <p>
	 * This local ID is guaranteed to be unique for this place but not across all
	 * places.
	 */
	public final int lid;

	/**
	 * The globally unique {@code long} ID of this {@link GlobalID} instance.
	 *
	 * @return a globally unique ID
	 */
	public long gid() {
		return (((long) home.id) << 32) + lid;
	}

	/**
	 * Constructs a new {@link GlobalID}.
	 */
	public GlobalID() {
		home = here();
		lid = count.getAndIncrement();
	}

	/**
	 * Constructor of an arbitrary GlobalID object.
	 * 
	 * @param h  home of the created global id
	 * @param id number of the global id
	 */
	public GlobalID(Place h, int id) {
		home = h;
		lid = id;
	}

	/**
	 * Associates the given value with this {@link GlobalID} instance.
	 *
	 * @param value the value to associate with this global ID
	 * @return the previous value
	 */
	public Object putHere(Object value) {
		final Object result = map.put(this, value == null ? NULL : value);
		return result == NULL ? null : result;
	}

	/**
	 * If this {@link GlobalID} instance is not already associated with a value
	 * associates it with the given value.
	 *
	 * @param value the value to associate with this global ID
	 * @return the previous value
	 */
	public Object putHereIfAbsent(Object value) {
		final Object result = map.putIfAbsent(this, value == null ? NULL : value);
		return result == NULL ? null : result;
	}

	/**
	 * Returns the value associated with this {@link GlobalID} instance.
	 *
	 * @return the current value
	 */
	public Object getHere() {
		final Object result = map.get(this);
		return result == NULL ? null : result;
	}

	/**
	 * Returns the value associated with this {@link GlobalID} instance if any, or
	 * {@code defaultValue} if none.
	 *
	 * @param defaultValue the default value
	 * @return the current or default value
	 */
	public Object getOrDefaultHere(Object defaultValue) {
		final Object result = map.getOrDefault(this, defaultValue);
		return result == NULL ? null : result;
	}

	/**
	 * Removes the value associated with this {@link GlobalID} instance if any.
	 *
	 * @return the removed value
	 */
	public Object removeHere() {
		final Object result = map.remove(this);
		return result == NULL ? null : result;
	}

	/**
	 * Removes the value associated with this {@link GlobalID} instance in a
	 * collection of places.
	 * <p>
	 * Masks {@link DeadPlaceException} instances if any.
	 *
	 * @param places where to remove the value
	 */
	public void remove(Collection<? extends Place> places) {
		final GlobalID that = this;
		finish(() -> {
			for (final Place p : places) {
				try {
					asyncAt(p, () -> {
						that.removeHere();
					});
				} catch (final DeadPlaceException e) {
					async(() -> {
						throw e;
					});
				}
			}
		});
	}

	@Override
	public String toString() {
		return "gid(" + gid() + ")";
	}

	@Override
	public boolean equals(Object that) {
		return that instanceof GlobalID ? gid() == ((GlobalID) that).gid() : false;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(gid());
	}
}
