package com.heartsong.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JLayer decoder with session-owned JavaSound output.
 *
 * <p>Control methods only change memory state and start daemon threads. File I/O,
 * device opening, writes, draining and closing happen off the calling thread.
 * A retired session only ever touches its own stream and output line.</p>
 *
 * <p>Listener callbacks run on a playback worker, after terminal state is published.
 * UI listeners should dispatch onto their UI thread. An already-dispatched callback
 * can overlap a later control call; generation-aware listeners should compare the
 * callback generation with {@link #getGeneration()} on the UI thread.</p>
 */
public final class Mp3Player {
	public interface Listener {
		void onTrackFinished();

		void onError(String message);

		/** Carries the playback identity across asynchronous UI dispatch. */
		default void onTrackFinished(long generation) {
			onTrackFinished();
		}

		/** Carries the playback identity while retaining legacy listener behavior. */
		default void onError(long generation, String message) {
			onError(message);
		}
	}

	private final Object stateLock = new Object();
	// Accessed under stateLock; retains retired workers until their resources are released.
	private final Set<Session> liveSessions = new HashSet<>();
	private long generation;
	private volatile Session active;
	private volatile float volume = 1.0f;
	private volatile boolean loop = true;
	private volatile Listener listener;

	private static final class Session {
		final long generation;
		final Path file;
		final AtomicReference<Output> output = new AtomicReference<>();
		volatile boolean cancelled;
		volatile float volume;
		volatile boolean loop;
		Thread worker;

		Session(long generation, Path file, boolean loop, float volume) {
			this.generation = generation;
			this.file = file;
			this.loop = loop;
			this.volume = volume;
		}
	}

	private static final class Output {
		final SourceDataLine line;
		final int rate;
		final int channels;
		final AtomicBoolean closing = new AtomicBoolean();

		Output(SourceDataLine line, int rate, int channels) {
			this.line = line;
			this.rate = rate;
			this.channels = channels;
		}

		void close() {
			if (!closing.compareAndSet(false, true)) {
				return;
			}
			// Each operation gets its own try: a failed stop must still release the device.
			try {
				line.stop();
			} catch (Exception ignored) {
			}
			try {
				line.flush();
			} catch (Exception ignored) {
			}
			try {
				line.close();
			} catch (Exception ignored) {
			}
		}
	}

	public void setVolume(float volume) {
		float value = clampVolume(volume);
		synchronized (stateLock) {
			this.volume = value;
			Session session = active;
			if (session != null) {
				session.volume = value;
			}
		}
	}

	public float getVolume() {
		return volume;
	}

	/** Includes asynchronous startup and the final drain; stop clears it immediately. */
	public boolean isPlaying() {
		return active != null;
	}

	/** Changes on play/stop/stopAndAwait; retained on natural completion or error. */
	public long getGeneration() {
		synchronized (stateLock) {
			return generation;
		}
	}

	/** Returns null after stop, failure or natural completion. */
	public Path getCurrentFile() {
		Session session = active;
		return session == null ? null : session.file;
	}

	public void setLoop(boolean loop) {
		synchronized (stateLock) {
			this.loop = loop;
			Session session = active;
			if (session != null) {
				session.loop = loop;
			}
		}
	}

	public boolean isLoop() {
		return loop;
	}

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	/** Replaces playback without waiting for the previous worker or device driver. */
	public void play(Path file, boolean loop, float volume) {
		Session previous;
		synchronized (stateLock) {
			previous = active;
			if (previous != null) {
				previous.cancelled = true;
			}
			this.loop = loop;
			this.volume = clampVolume(volume);
			Session session = new Session(++generation, file, loop, this.volume);
			Thread worker = new Thread(() -> decodeLoop(session),
					"HeartSong-MP3-" + session.generation);
			worker.setDaemon(true);
			session.worker = worker;
			active = session;
			liveSessions.add(session);
			worker.start();
		}
		cancelOutput(previous);
	}

	/** Detaches playback immediately; no join, file I/O or JavaSound call on this thread. */
	public void stop() {
		Session previous;
		synchronized (stateLock) {
			previous = active;
			active = null;
			++generation;
			if (previous != null) {
				previous.cancelled = true;
			}
		}
		cancelOutput(previous);
	}

	/**
	 * Background-only stop barrier, useful before replacing a Windows-open MP3 file.
	 * Includes previously retired workers as well as the current session. Returns
	 * true once their worker threads exit, or false on timeout. Zero means a poll,
	 * rather than an unlimited wait. Playback started after the snapshot is excluded.
	 */
	public boolean stopAndAwait(long timeoutMillis) throws InterruptedException {
		if (timeoutMillis < 0) {
			throw new IllegalArgumentException("timeoutMillis must be >= 0");
		}
		Session[] sessions;
		synchronized (stateLock) {
			active = null;
			++generation;
			sessions = liveSessions.toArray(new Session[0]);
			for (Session session : sessions) {
				session.cancelled = true;
			}
		}
		long budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		long started = System.nanoTime();
		for (Session session : sessions) {
			cancelOutput(session);
		}
		for (Session session : sessions) {
			Thread worker = session.worker;
			if (worker == Thread.currentThread()) {
				return false;
			}
			while (worker.isAlive()) {
				long remaining = budget - (System.nanoTime() - started);
				if (remaining <= 0) {
					return false;
				}
				TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
			}
		}
		return true;
	}

	private static void cancelOutput(Session session) {
		if (session == null) {
			return;
		}
		session.worker.interrupt();
		Output output = session.output.get();
		if (output != null) {
			// A separate closer per retired session avoids a blocked driver serializing stops.
			Thread closer = new Thread(output::close,
					"HeartSong-MP3-stop-" + session.generation);
			closer.setDaemon(true);
			closer.start();
		}
	}

	/**
	 * Decodes the first MPEG frame, with no JavaSound/device access.
	 * Intended for background song selection. Returns true on success and throws
	 * IOException for missing, empty, malformed or undecodable input. This probes
	 * the first frame only, rather than scanning the entire track.
	 */
	public static boolean verify(Path file) throws IOException {
		try (InputStream in = openInput(file)) {
			Bitstream bitstream = new Bitstream(in);
			try {
				Header header = bitstream.readFrame();
				if (header == null) {
					throw new IOException("MP3 contains no audio frames: " + file);
				}
				try {
					validateSample((SampleBuffer) new Decoder().decodeFrame(header, bitstream));
				} finally {
					bitstream.closeFrame();
				}
				return true;
			} finally {
				bitstream.close();
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Invalid MP3: " + file + " (" + errorMessage(e) + ")", e);
		}
	}

	private void decodeLoop(Session session) {
		boolean finished = false;
		String error = null;
		try {
			while (!session.cancelled) {
				boolean decodedAudio = false;
				try (InputStream in = openInput(session.file)) {
					Bitstream bitstream = new Bitstream(in);
					Decoder decoder = new Decoder();
					try {
						while (!session.cancelled) {
							Header header = bitstream.readFrame();
							if (header == null) {
								break;
							}
							try {
								SampleBuffer sample = (SampleBuffer) decoder.decodeFrame(header, bitstream);
								validateSample(sample);
								decodedAudio = true;
								write(session, sample);
							} finally {
								bitstream.closeFrame();
							}
						}
					} finally {
						bitstream.close();
					}
				}
				if (session.cancelled) {
					break;
				}
				if (!decodedAudio) {
					throw new IOException("MP3 contains no audio frames: " + session.file);
				}
				if (!session.loop) {
					// Keep the session visible throughout drain so stop can abort it.
					drain(session, session.output.get());
					if (!session.cancelled && session.loop) {
						continue;
					}
					finished = !session.cancelled;
					break;
				}
			}
		} catch (Exception e) {
			if (!session.cancelled) {
				error = errorMessage(e);
			}
		} finally {
			Output output = session.output.get();
			if (output != null) {
				output.close();
				session.output.compareAndSet(output, null);
			}
			try {
				complete(session, finished, error);
			} finally {
				synchronized (stateLock) {
					liveSessions.remove(session);
				}
			}
		}
	}

	private static InputStream openInput(Path file) throws IOException {
		if (file == null || !Files.isRegularFile(file)) {
			throw new IOException("Missing file: " + file);
		}
		return new BufferedInputStream(Files.newInputStream(file), 1 << 16);
	}

	private static void validateSample(SampleBuffer sample) throws IOException {
		if (sample == null || sample.getBufferLength() <= 0
				|| sample.getSampleFrequency() <= 0 || sample.getChannelCount() <= 0
				|| sample.getBufferLength() % sample.getChannelCount() != 0) {
			throw new IOException("MP3 frame contains no valid PCM audio");
		}
	}

	private void write(Session session, SampleBuffer sample) throws Exception {
		if (session.cancelled) {
			return;
		}
		int channels = sample.getChannelCount();
		Output output = ensureLine(session, sample.getSampleFrequency(), channels);
		if (output == null || session.cancelled) {
			return;
		}
		short[] pcm = sample.getBuffer();
		int length = sample.getBufferLength();
		byte[] bytes = new byte[length * 2];
		float gain = session.volume;
		for (int i = 0; i < length; i++) {
			int value = Math.max(-32768, Math.min(32767, Math.round(pcm[i] * gain)));
			bytes[i * 2] = (byte) value;
			bytes[i * 2 + 1] = (byte) (value >> 8);
		}
		// Small frame-aligned writes bound queued PCM and check cancellation frequently.
		int frameSize = channels * 2;
		int chunkSize = Math.max(frameSize, 1024 / frameSize * frameSize);
		for (int offset = 0; offset < bytes.length && !session.cancelled;) {
			int written = output.line.write(bytes, offset, Math.min(chunkSize, bytes.length - offset));
			if (written <= 0) {
				if (session.cancelled) {
					return;
				}
				throw new IOException("Audio device stopped accepting PCM data");
			}
			offset += written;
		}
	}

	private Output ensureLine(Session session, int rate, int channels) throws Exception {
		Output previous = session.output.get();
		if (previous != null) {
			if (previous.rate == rate && previous.channels == channels) {
				return previous;
			}
			drain(session, previous);
			previous.close();
			session.output.compareAndSet(previous, null);
		}
		if (session.cancelled) {
			return null;
		}
		AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
		SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
		Output output = new Output(line, rate, channels);
		try {
			// About 40 ms of PCM; a slow driver still stays off the caller's thread.
			line.open(format, Math.max(256, rate / 25) * channels * 2);
			// Publish only after open: closing an unopened line can race its later open.
			session.output.set(output);
			if (session.cancelled) {
				output.close();
				return null;
			}
			try {
				if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
					FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
					control.setValue(Math.max(control.getMinimum(), Math.min(control.getMaximum(), 0.0f)));
				}
			} catch (Exception ignored) {
			}
			line.start();
			return output;
		} catch (Exception e) {
			output.close();
			throw e;
		}
	}

	private static void drain(Session session, Output output) {
		if (output != null && !session.cancelled) {
			output.line.drain();
		}
	}

	private void complete(Session session, boolean finished, String error) {
		Listener callback;
		synchronized (stateLock) {
			if (active != session || generation != session.generation || session.cancelled) {
				return;
			}
			active = null;
			callback = listener;
		}
		if (callback != null) {
			try {
				if (error != null) {
					callback.onError(session.generation, error);
				} else if (finished) {
					callback.onTrackFinished(session.generation);
				}
			} catch (RuntimeException ignored) {
				// Listener failures belong to the listener, rather than the audio lifecycle.
			}
		}
	}

	private static float clampVolume(float value) {
		return Float.isNaN(value) ? 1.0f : Math.max(0.0f, Math.min(2.0f, value));
	}

	private static String errorMessage(Exception error) {
		String message = error.getMessage();
		return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}
}
