package cz.gsprep.core;

/** Progress reporting + cooperative cancellation for long GPU jobs. */
public interface Progress {
    /** fraction 0..1 of the current image. */
    void update(float fraction);

    boolean isCancelled();

    final class CancelledException extends Exception {
        public CancelledException() { super("Zrušeno"); }
    }
}
