/*******************************************************************************
 * Copyright (c) 2020 Handy Tools for Distributed Computing (HanDist) project.
 *
 * This program and the accompanying materials are made available to you under 
 * the terms of the Eclipse Public License 1.0 which accompanies this 
 * distribution, and is available at https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 *******************************************************************************/
package apgas.mpi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import apgas.Configuration;
import apgas.GlobalRuntime;
import apgas.impl.Config;
import apgas.impl.Launcher;
import mpi.Comm;
import mpi.MPI;
import mpi.MPIException;

/**
 * The {@link MPILauncher} class implements a launcher for the apgas runtime
 * using MPI. Programs should be run in the following way:
 * <p>
 * <em>mpirun -np &lt;nb hosts&gt; -host &lt; comma-seperated list of hosts&gt;
 * java -cp &lt;classpath&gt; apgas.mpi.MPILauncher &lt;main class to launch&gt;
 * &lt;arguments for said class&gt;</em>
 * <p>
 * For instance, to launch the HelloWorld example with message "Hello" on 4
 * servers, a possible command would be:
 * <p>
 * <em>mpirun -np 4 -host piccolo04,piccolo05,piccolo06,piccolo07 java -cp
 * apgas.jar:apgasExamples.jar:kryo.jar:mpi.jar:reflectasm.jar:hazelcast.jar:minlog.jar:objenesis.jar
 * apgas.mpi.MPILauncher apgas.examples.HelloWorld Hello</em>
 * <p>
 * This launcher allows some initializations and finalizations to be made using
 * a "plugin" mechanism. Programmers can create a custom class to be used as
 * main that will add a plugin to this launcher using
 * {@link MPILauncher#registerPlugin(Plugin)} before calling this class's main
 * method with the program arguments.
 * 
 * @author Toshiyuki, Kamada
 */
final public class MPILauncher implements Launcher {
	public static interface Plugin {
		public void beforeFinalize(int rank, Comm com) throws MPIException;

		public String getName();

		public void init(int rank, Comm cocm) throws MPIException;
	}

	/** Identifier of this place */
	static int commRank;
	/** Number of places in the system */
	static int commSize;
	/**
	 * boolean flag to prevent the runtime to call method
	 * {@link #mpiCustomFinalize(int, Comm)} twice
	 */
	static boolean finalizedCalled = false;

	static List<Plugin> plugins = new ArrayList<>();
	/**
	 * Set in the main method according to the value set by
	 * {@link Configuration#APGAS_VERBOSE_LAUNCHER}
	 */
	static boolean verboseLauncher;

	/**
	 * Deserializer method
	 *
	 * @param barray byte array input
	 * @return Object constructed from the input
	 * @throws IOException            if an I/O occurs
	 * @throws ClassNotFoundException if the class could not be identified
	 * @see #serializeToByteArray(Serializable)
	 */
	static Object deserializeFromByteArray(byte[] barray) throws IOException, ClassNotFoundException {
		final ByteArrayInputStream bais = new ByteArrayInputStream(barray);
		final ObjectInputStream ois = new ObjectInputStream(bais);
		return ois.readObject();
	}

	/**
	 * Converts an int array back into the String it represents
	 *
	 * @param src the integer array to be converted back into a String
	 * @return the constructed String
	 * @see #stringToIntArray(String)
	 */
	static String intArrayToString(int[] src) {
		final char[] charArray = new char[src.length];
		for (int i = 0; i < src.length; i++) {
			charArray[i] = (char) src[i];
		}
		final String str = new String(charArray);
		return str;
	}

	/**
	 * Main method of the MPILauncher class
	 * <p>
	 * Sets up the APGAS environment using an mpi launcher. Rank 0 of the processes
	 * will launch the main method of the class specified as parameter with the
	 * arguments specified afterward. Other processes will only setup their apgas
	 * environment and wait for incoming activities.
	 * <p>
	 * This main method takes at least one argument, the fully qualified name of the
	 * class whose main method is to be run. Arguments for that class' main need to
	 * to follow that first argument.
	 *
	 * @param args Path to the class whose main method is to be run, followed by the
	 *             arguments of that classe's main method.
	 * @throws Exception if MPI exception occur
	 */
	public static void main(String[] args) throws Exception {

		MPI.Init(args);
		commRank = MPI.COMM_WORLD.getRank();
		commSize = MPI.COMM_WORLD.getSize();
		MPI.COMM_WORLD.setErrhandler(MPI.ERRORS_RETURN);

		verboseLauncher = Boolean.parseBoolean(System.getProperty(Configuration.APGAS_VERBOSE_LAUNCHER, "false"));

		// Determine is we are using MPJ, in which case the args will need to be
		// adjusted
		boolean isMPJ = false;
		try {
			final Class<?> mpjdevCommClass = Class.forName("mpjdev.Comm");
			isMPJ = (mpjdevCommClass != null);
		} catch (Exception e) {
			// Ignore any exception
		}

		if (verboseLauncher) {
			System.err.print("[MPILauncher] Args:");
			for (String s : args) {
				System.err.print(" " + s);
			}
			System.err.println();
			System.err.println("[MPILauncher] Running with MPJ ? " + isMPJ);
			System.err.println("[MPILauncher] command: " + java.util.Arrays.asList(args));
			System.err.println("[MPILauncher] rank = " + commRank);
		}

		if (args.length < 1) {
			System.err.println("[MPILauncher] Error Main Class Required");
			MPI.Finalize();
			System.exit(0);
		}

		/*
		 * Extracts the arguments destined for the main method of the specified class.
		 */
		final int offset = isMPJ ? 4 : 1;
		final String[] newArgs = new String[args.length - offset];
		System.arraycopy(args, offset, newArgs, 0, args.length - offset);

		// Sets the number of places according to the arguments given to `mpirun`
		// command
		System.setProperty(Configuration.APGAS_PLACES, Integer.toString(commSize));
		// Sets the launcher to be of MPILauncher class. This will make the apgas
		// runtime use the MPILauncher shutdown method when the apgas shutdown
		// method is launched
		System.setProperty(Config.APGAS_LAUNCHER, MPILauncher.class.getCanonicalName());

		// If "kryo==true", the program uses KryoSerializer instead of JavaSerializer.
		boolean kryo = true;

		if (!kryo) {
			// Sets the APGAS_SERIALIZATION to "java". This will make the apgas runtime
			// use the JavaSerializer for all classes, instead of using KryoSerializer.

			System.setProperty(Config.APGAS_SERIALIZATION, "java");
		} else {
			apgas.impl.KryoSerializer
					.setInstantiatorStrategy(new apgas.impl.KryoSerializer.DefaultForColInstantiatorStrategy());
		}

		/*
		 * If this place is the "master", i.e. rank, launches the main method of the
		 * class specified as parameter. If it is not the "master", sets up the APGAS
		 * runtime and waits till asynchronous tasks are submitted to this place.
		 */
		if (commRank == 0) {

			String mainClassName = args[isMPJ ? 3 : 0];
			try {
				GlobalRuntime.getRuntime();
				final Method mainMethod = Class.forName(mainClassName).getMethod("main", String[].class);
				final Object[] mainArgs = new Object[1];
				mainArgs[0] = newArgs;
				mpiCustomSetup(commRank, MPI.COMM_WORLD);
				mainMethod.invoke(null, mainArgs);
			} catch (final ClassNotFoundException e) {
				System.err.println("[MPILauncher] Error: Class " + mainClassName + " could not be found");
			} catch (final NoSuchMethodException e) {
				System.err.println("[MPILauncher] Error: Class " + mainClassName + " does not have a main method");
			} catch (final Exception e) {
				e.printStackTrace();
			}
		} else {
			// Class<?> mainClass = Class.forName(args[isMPJ? 3:0]);
			slave();
			mpiCustomSetup(commRank, MPI.COMM_WORLD);
		}

		try {
			mpiCustomFinalize(commRank, MPI.COMM_WORLD);
			// MPI.Finalize();
			System.exit(0);
		} catch (final MPIException e) {
			System.err.println("[MPILauncher] Error on Finalize - main method at rank " + commRank);
			e.printStackTrace();
		}

	}

	public synchronized static void mpiCustomFinalize(int rank, Comm world) throws MPIException {
		if (!finalizedCalled) {
			java.util.Collections.reverse(plugins);
			for (Plugin plugin : plugins) {
				plugin.beforeFinalize(rank, world);
			}
			MPI.Finalize();
			finalizedCalled = true;
		}
	}

	public static void mpiCustomSetup(int rank, Comm world) throws MPIException {
		for (Plugin plugin : plugins) {
			plugin.init(rank, world);
		}
	}

	public static void registerPlugin(Plugin plugin) {
		if (verboseLauncher) {
			System.err.println("[MPILauncher] " + plugin.getName() + " registered.");
		}
		for (Plugin p : plugins) {
			if (p.getName().equals(plugin.getName())) {
				System.err.println("[Warning][MPILauncher#registerPlugin] The plugin " + plugin.getName()
						+ " was previously registered," + " skipping duplicate.");
			}
		}
		plugins.add(plugin);
	}

	/**
	 * Serializer method
	 *
	 * @param obj Object to be serialized
	 * @return array of bytes
	 * @throws IOException if an I/O exception occurs
	 * @see #deserializeFromByteArray(byte[])
	 */
	static byte[] serializeToByteArray(Serializable obj) throws IOException {
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		final ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(obj);
		return baos.toByteArray();
	}

	/**
	 * Sets up the apgas runtime for it to wait for activities to be spawned on this
	 * place.
	 *
	 * @throws Exception if some problem occurs with the MPI runtime
	 */
	static void slave() throws Exception {

		final int[] msglen = new int[1];
		MPI.COMM_WORLD.bcast(msglen, 1, MPI.INT, 0);
		final byte[] msgbuf = new byte[msglen[0]];
		MPI.COMM_WORLD.bcast(msgbuf, msglen[0], MPI.BYTE, 0);
		final String[] command = (String[]) deserializeFromByteArray(msgbuf);
		if (verboseLauncher) {
			System.err.println("command@worker[" + MPI.COMM_WORLD.getRank() + "]: " + java.util.Arrays.asList(command));
		}
		for (int i = 1; i < command.length; i++) {
			final String term = command[i];

			if (term.startsWith("-D")) {
				final String[] kv = term.substring(2).split("=", 2);
				if (verboseLauncher) {
					System.err.println("[" + commRank + "] setProperty \"" + kv[0] + "\" = \"" + kv[1] + "\"");
				}
				System.setProperty(kv[0], kv[1]);
			}
		}

		GlobalRuntime.getRuntime();
	}

	/**
	 * Converts a String to an int array
	 *
	 * @param src String to be converted
	 * @return array of integer corresponding to the given parameter
	 * @see #intArrayToString(int[])
	 */
	static int[] stringToIntArray(String src) {
		final char[] charArray = src.toCharArray();
		final int[] intArray = new int[charArray.length];
		for (int i = 0; i < charArray.length; i++) {
			intArray[i] = charArray[i];
		}
		return intArray;
	}

	/**
	 * Constructs a new {@link MPILauncher} instance.
	 */
	public MPILauncher() {
	}

	/**
	 * Checks that all the processes launched are healthy.
	 * <p>
	 * Current implementation of {@link MPILauncher} always returns true.
	 *
	 * @return true if all subprocesses are healthy
	 */
	@Override
	public boolean healthy() {
		// TODO
		return true;
	}

	/**
	 * Launches n processes with the given command line and host list. The first
	 * host of the list is skipped. If the list is incomplete, the last host is
	 * repeated.
	 *
	 * @param n       number of processes to launch
	 * @param command command line
	 * @param hosts   host list (not null, not empty, but possibly incomplete)
	 * @param verbose dumps the executed commands to stderr
	 * @throws Exception if launching fails
	 */
	@Override
	public void launch(int n, List<String> command, List<String> hosts, boolean verbose) throws Exception {

		if (n + 1 != commSize) {
			System.err.println("[MPILauncher] " + Configuration.APGAS_PLACES
					+ " should be equal to number of MPI processes " + commSize);
			MPI.Finalize();
			System.exit(-1);
		}

		final byte[] baCommand = serializeToByteArray(command.toArray(new String[command.size()]));
		final int[] msglen = new int[1];
		msglen[0] = baCommand.length;
		MPI.COMM_WORLD.bcast(msglen, 1, MPI.INT, 0);
		MPI.COMM_WORLD.bcast(baCommand, msglen[0], MPI.BYTE, 0);
	}

	/**
	 * Launches one process with the given command line at the specified host.
	 * <p>
	 * Should not be called in practice as every java processes are supposed to be
	 * launched using the `mpirun` command. Implementation will print an "Internal
	 * error" on the {@link System#err} before calling for the program's termination
	 * and exiting with return code -1.
	 *
	 * @param command command line
	 * @param host    host
	 * @param verbose dumps the executed commands to stderr
	 * @return the process object
	 * @throws Exception if launching fails
	 */
	@Override
	public Process launch(List<String> command, String host, boolean verbose) throws Exception {

		System.err.println("[MPILauncher] Internal Error");
		mpiCustomFinalize(MPI.COMM_WORLD.getRank(), MPI.COMM_WORLD);
		System.exit(-1);

		return null;
	}

	/**
	 * Shuts down the local process launched using MPI.
	 */
	@Override
	public void shutdown() {
		try {
			mpiCustomFinalize(commRank, MPI.COMM_WORLD);
		} catch (MPIException e) {
			e.printStackTrace();
		}
	}
}
