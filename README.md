# APGAS for Java

This project is a fork from the APGAS for Java library first developed by the X10 team of IBM. The original library can be found here: [github.com/x10-lang/apgas](https://github.com/x10-lang/apgas). 

Some modifications were made to resolve some bugs and integrate new features. 

## License

This project is licensed under the terms of the Eclipse Public License v1.0. 

## Changes Compared to the standard Apgas for Java library

* **Addition of an MPI launcher**

  This launcher allows us to launch the Apgas runtime with an `mpirun -np 4 java -cp ...` command rather than using the default Ssh launcher. This launcher is absolutely needed to launch Java + APGAS programs on supercomputers as we cannot use Ssh to connect to compute nodes. 

  The files concerned are in the `apgas.mpi` package.

* **Correction of a serialization bug**

  We faced a serialization bug with exceptions that are serialized and gathered in the `MultipleException` wrapper. The problem was that a static singleton member was not transmitted, causing `NullPointerExceptions` when trying to print the error. This was corrected by using a little bit of reflection upon receiving a remote error in file `apgas.impl.DefaultFinish`. 

* **Addition of special constructs for the distributed collection GLB**

  To use the classic lifeline scheme to detect global termination of operations, we needed some special task which are registered with multiple Finish at the same time. These additional Finish are kept in an extra member in class `Task`. As a programmer, these additional features are presented as static methods in class `apgas.ExtendedConstructs` in order to avoid polluting the standard `apgas.Constructs`.   

* **Tweaks to serialization**

  Ongoing project