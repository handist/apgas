package apgas;

import apgas.impl.Finish;

public final class ExtendedConstructs {

	/** No initialization of instances */
	private ExtendedConstructs() {
	}

	public static Finish currentFinish() {
		return GlobalRuntime.getRuntimeImpl().currentFinish();
	}

	/**
	 * Spawns an asynchronous task on the specified place which is registered with
	 * the current finish AND all the finish given as parameter.
	 * <p>
	 * All the extra finish instances should have at least one active activity
	 * remaining on the place this method is called. Otherwise, the global
	 * termination detection will fail. s
	 * 
	 * @param p        destination place
	 * @param j        job to perform
	 * @param coFinish extra finish instances in which the given job
	 */
	public static void asyncAtWithCoFinish(Place p, SerializableJob j, Finish... coFinish) {
		GlobalRuntime.getRuntimeImpl().asyncAtWithCoFinish(p, j, coFinish);
	}

	/**
	 * Spawns an asynchronous task belonging outside of the current worker's finish.
	 * Instead the provided job will be handled by the specified finish.
	 * 
	 * @param f finish under which the activity needs to be spawned
	 * @param j activity to spawn
	 */
	public static void asyncDifferentFinish(Finish f, Job j) {
		GlobalRuntime.getRuntimeImpl().asyncDifferentFinish(f, j);
	}
}
