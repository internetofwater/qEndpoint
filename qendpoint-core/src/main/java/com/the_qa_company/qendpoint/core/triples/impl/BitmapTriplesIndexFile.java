package com.the_qa_company.qendpoint.core.triples.impl;

import com.the_qa_company.qendpoint.core.compact.bitmap.AdjacencyList;
import com.the_qa_company.qendpoint.core.compact.bitmap.Bitmap;
import com.the_qa_company.qendpoint.core.compact.bitmap.Bitmap64Big;
import com.the_qa_company.qendpoint.core.compact.bitmap.BitmapFactory;
import com.the_qa_company.qendpoint.core.compact.bitmap.ModifiableBitmap;
import com.the_qa_company.qendpoint.core.compact.sequence.DynamicSequence;
import com.the_qa_company.qendpoint.core.compact.sequence.Sequence;
import com.the_qa_company.qendpoint.core.compact.sequence.SequenceFactory;
import com.the_qa_company.qendpoint.core.compact.sequence.SequenceLog64BigDisk;
import com.the_qa_company.qendpoint.core.enums.TripleComponentOrder;
import com.the_qa_company.qendpoint.core.exceptions.IllegalFormatException;
import com.the_qa_company.qendpoint.core.exceptions.SignatureIOException;
import com.the_qa_company.qendpoint.core.iterator.utils.AsyncIteratorFetcher;
import com.the_qa_company.qendpoint.core.iterator.utils.ExceptionIterator;
import com.the_qa_company.qendpoint.core.iterator.utils.MapIterator;
import com.the_qa_company.qendpoint.core.listener.MultiThreadListener;
import com.the_qa_company.qendpoint.core.listener.ProgressListener;
import com.the_qa_company.qendpoint.core.options.HDTOptions;
import com.the_qa_company.qendpoint.core.options.HDTOptionsKeys;
import com.the_qa_company.qendpoint.core.triples.TripleID;
import com.the_qa_company.qendpoint.core.util.BitUtil;
import com.the_qa_company.qendpoint.core.util.concurrent.KWayMerger;
import com.the_qa_company.qendpoint.core.util.io.CloseMappedByteBuffer;
import com.the_qa_company.qendpoint.core.util.io.CloseSuppressPath;
import com.the_qa_company.qendpoint.core.util.io.Closer;
import com.the_qa_company.qendpoint.core.util.io.CountInputStream;
import com.the_qa_company.qendpoint.core.util.io.IOUtil;
import com.the_qa_company.qendpoint.core.util.listener.ListenerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static java.lang.String.format;

/**
 * File containing a BitmapTriples index
 *
 * @author Antoine Willerval
 */
public class BitmapTriplesIndexFile implements BitmapTriplesIndex {

	private static final Logger logger = LoggerFactory.getLogger(BitmapTriplesIndexFile.class);

	/**
	 * Get the path for an order for a hdt file
	 *
	 * @param hdt   hdt path
	 * @param order order
	 * @return index path
	 */
	public static Path getIndexPath(Path hdt, TripleComponentOrder order) {
		return hdt.resolveSibling(hdt.getFileName() + "." + order.name().toLowerCase() + ".idx");
	}

	/**
	 * Compute triples signature
	 *
	 * @param triples triples
	 * @return signature
	 */
	public static long signature(BitmapTriples triples) {
		return 0x484454802020L ^ triples.getNumberOfElements();
	}

	public static final String MAGIC_STR = "$HDTIDX1";
	public static final byte[] MAGIC = MAGIC_STR.getBytes(StandardCharsets.US_ASCII);
	public static final byte[] MAGIC_V0 = "$HDTIDX0".getBytes(StandardCharsets.US_ASCII);

	/**
	 * Map a file from a file
	 *
	 * @param file     file
	 * @param channel  channel
	 * @param triples  triples
	 * @param allowOld allow old files
	 * @return index
	 * @throws IOException io
	 */
	public static BitmapTriplesIndex map(Path file, FileChannel channel, BitmapTriples triples, boolean allowOld)
			throws IOException {

		long headerSize = MAGIC.length;
		try (CloseMappedByteBuffer header = IOUtil.mapChannel(file, channel, FileChannel.MapMode.READ_ONLY, 0,
				MAGIC.length + 8)) {
			byte[] magicRead = new byte[MAGIC.length];

			header.get(magicRead);

			if (Arrays.equals(magicRead, MAGIC)) {
				headerSize += 8; // signature

				long signature = header.order(ByteOrder.LITTLE_ENDIAN).getLong(magicRead.length);

				long currentSignature = signature(triples);
				if (signature != currentSignature) {
					throw new SignatureIOException(
							format("Wrong signature for file 0x%x != 0x%x", signature, currentSignature));
				}

			} else {
				if (!allowOld) {
					throw new IOException(
							format("Invalid magic for %s: %s", file, new String(magicRead, StandardCharsets.US_ASCII)));
				}
				logger.warn("Reading {} with {}!={} magic", file, new String(magicRead, StandardCharsets.US_ASCII),
						MAGIC_STR);

				if (Arrays.equals(magicRead, MAGIC_V0)) {
					// reading v0
					logger.debug("Use v0 magic for {}", file);
				} else {
					throw new IOException(
							format("Unknown magic for %s: %s", file, new String(magicRead, StandardCharsets.US_ASCII)));
				}
			}
		}

		CountInputStream stream = new CountInputStream(new BufferedInputStream(Channels.newInputStream(channel)));
		stream.skipNBytes(headerSize);

		String orderCfg = IOUtil.readSizedString(stream, ProgressListener.ignore());

		TripleComponentOrder order = TripleComponentOrder.valueOf(orderCfg);

		Sequence seqY = SequenceFactory.createStream(stream, file.toFile());
		Bitmap bitY = BitmapFactory.createBitmap(stream);
		bitY.load(stream, ProgressListener.ignore());

		Sequence seqZ = SequenceFactory.createStream(stream, file.toFile());
		Bitmap bitZ = BitmapFactory.createBitmap(stream);
		bitZ.load(stream, ProgressListener.ignore());

		return new BitmapTriplesIndexFile(seqY, seqZ, bitY, bitZ, order);
	}

	/**
	 * Generate an index in a particular destination
	 *
	 * @param bitmapTriples bitmap triples container
	 * @param origin        triples to convert
	 * @param destination   destination path
	 * @param order         order to build
	 * @param spec          ixd spec
	 * @param mtlistener    listener
	 * @throws IOException ioe
	 */
	public static void generateIndex(BitmapTriples bitmapTriples, BitmapTriplesIndex origin, Path destination,
			TripleComponentOrder order, HDTOptions spec, MultiThreadListener mtlistener) throws IOException {
		MultiThreadListener listener = MultiThreadListener.ofNullable(mtlistener);
		Path diskLocation;
		if (bitmapTriples.diskSequence) {
			diskLocation = bitmapTriples.diskSequenceLocation.createOrGetPath();
		} else {
			diskLocation = Files.createTempDirectory("bitmapTriples");
		}
		int workers = (int) spec.getInt(HDTOptionsKeys.BITMAPTRIPLES_DISK_WORKER_KEY,
				Runtime.getRuntime()::availableProcessors);
		// check and set default values if required
		if (workers <= 0) {
			throw new IllegalArgumentException("Number of workers should be positive!");
		}
		long chunkSize = spec.getInt(HDTOptionsKeys.BITMAPTRIPLES_DISK_CHUNK_SIZE_KEY,
				() -> BitmapTriples.getMaxChunkSizeDiskIndex(workers));
		if (chunkSize < 0) {
			throw new IllegalArgumentException("Negative chunk size!");
		}
		long maxFileOpenedLong = spec.getInt(HDTOptionsKeys.BITMAPTRIPLES_DISK_MAX_FILE_OPEN_KEY, 1024);
		int maxFileOpened;
		if (maxFileOpenedLong < 0 || maxFileOpenedLong > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("maxFileOpened should be positive!");
		} else {
			maxFileOpened = (int) maxFileOpenedLong;
		}
		long kwayLong = spec.getInt(HDTOptionsKeys.BITMAPTRIPLES_DISK_KWAY_KEY,
				() -> Math.max(1, BitUtil.log2(maxFileOpened / workers)));
		int k;
		if (kwayLong <= 0 || kwayLong > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("kway can't be negative!");
		} else {
			k = 1 << ((int) kwayLong);
		}
		long bufferSizeLong = spec.getInt(HDTOptionsKeys.BITMAPTRIPLES_DISK_BUFFER_SIZE_KEY,
				CloseSuppressPath.BUFFER_SIZE);
		int bufferSize;
		if (bufferSizeLong > Integer.MAX_VALUE - 5L || bufferSizeLong <= 0) {
			throw new IllegalArgumentException("Buffer size can't be negative or bigger than the size of an array!");
		} else {
			bufferSize = (int) bufferSizeLong;
		}

		try (CloseSuppressPath workDir = CloseSuppressPath
				.of(diskLocation.resolve("triplesort-" + order.name().toLowerCase()))) {
			workDir.mkdirs();
			workDir.closeWithDeleteRecurse();

			TripleComponentOrder oldOrder = origin.getOrder();
			ExceptionIterator<TripleID, IOException> sortedIds = null;
			ModifiableBitmap bitY = null;
			ModifiableBitmap bitZ = null;
			DynamicSequence seqY = null;
			DynamicSequence seqZ = null;
			try {
				boolean useFastSort = oldOrder.getSubjectMapping() == order.getSubjectMapping()
						&& spec.getBoolean("debug.bitmaptriples.allowFastSort", true);

				int ss = BitUtil.log2(origin.getBitmapY().countOnes());
				int ps = origin.getSeqY().sizeOf();
				int os = origin.getSeqZ().sizeOf();

				TripleID logTriple;
				if (useFastSort) {
					// no need to sort everything
					sortedIds = SortGroupSubjectIterator.of(
							// we force the read as a SPO because we know we
							// won't have to reorder them
							ExceptionIterator
									.<TripleID, IOException>of(
											new BitmapTriplesIterator(origin, new TripleID(), TripleComponentOrder.SPO))
									.map(next -> {
										TripleID v = next.clone();
										// we switch the object and predicate
										v.setObject(next.getPredicate());
										v.setPredicate(next.getObject());
										return v;
									}));
					logTriple = new TripleID(ss, os, ps);
				} else {
					origin = bitmapTriples;
					oldOrder = origin.getOrder();
					sortedIds = new DiskTriplesReorderSorter(workDir,
							new AsyncIteratorFetcher<>(new MapIterator<>(
									new BitmapTriplesIterator(origin, new TripleID()), TripleID::clone)),
							listener, bufferSize, chunkSize, k, oldOrder, order).sort(workers);

					logTriple = new TripleID(ss, ps, os);

					// we swap the order to find the new allocation numbits
					TripleOrderConvert.swapComponentOrder(logTriple, oldOrder, order);
				}

				// System.out.println(logTriple);

				int ySize = (int) logTriple.getPredicate();
				int zSize = (int) logTriple.getObject();

				long count = bitmapTriples.getNumberOfElements();
				workDir.mkdirs();
				workDir.closeWithDeleteRecurse();
				bitY = Bitmap64Big.disk(workDir.resolve("bity"), count);
				bitZ = Bitmap64Big.disk(workDir.resolve("bitZ"), count);

				origin.getSeqY().sizeOf();

				seqY = new SequenceLog64BigDisk(workDir.resolve("seqy"), ySize, count, false, true);
				seqZ = new SequenceLog64BigDisk(workDir.resolve("seqz"), zSize, count, false, true);

				long lastX = 0;
				long lastY = 0;
				long lastZ = 0;

				// filling index

				long x, y, z;
				TripleID tid = null;
				long numTriples = 0;
				try {
					while (sortedIds.hasNext()) {
						tid = sortedIds.next();

						x = tid.getSubject();
						y = tid.getPredicate();
						z = tid.getObject();

						if (x == 0 || y == 0 || z == 0) {
							throw new IllegalFormatException("None of the components of a triple can be null");
						}

						if (numTriples == 0) {
							seqY.append(y);
							seqZ.append(z);
						} else if (lastX != x) {
							if (x != lastX + 1) {
								throw new IllegalFormatException("Upper level must be increasing and correlative: " + x
										+ " != " + lastX + " + " + 1 + " for " + tid);
							}

							// X changed
							bitY.append(true);
							seqY.append(y);

							bitZ.append(true);
							seqZ.append(z);
						} else if (y != lastY) {
							if (y < lastY) {
								throw new IllegalFormatException(
										"Middle level must be increasing for each parent. " + tid);
							}

							// Y changed
							bitY.append(false);
							seqY.append(y);

							bitZ.append(true);
							seqZ.append(z);
						} else {
							if (z < lastZ) {
								throw new IllegalFormatException(
										"Lower level must be increasing for each parent. " + tid);
							}

							// Z changed
							bitZ.append(false);
							seqZ.append(z);
						}

						lastX = x;
						lastY = y;
						lastZ = z;

						ListenerUtil.notifyCond(listener, "Converting to BitmapTriples", numTriples, numTriples, count);
						numTriples++;
					}
				} catch (RuntimeException e) {
					throw new IOException("Error when compressing triples " + tid + " " + oldOrder + " -> " + order
							+ (useFastSort ? " [fast]" : " [un]"), e);
				}

				if (numTriples > 0) {
					bitY.append(true);
					bitZ.append(true);
				}

				assert numTriples == bitmapTriples.getNumberOfElements();

				seqY.aggressiveTrimToSize();
				seqZ.trimToSize();

				// saving the index
				try (BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(destination))) {
					output.write(MAGIC);
					IOUtil.writeLong(output, signature(bitmapTriples));

					IOUtil.writeSizedString(output, order.name(), listener);

					seqY.save(output, listener);
					bitY.save(output, listener);

					seqZ.save(output, listener);
					bitZ.save(output, listener);

					// no need for CRC I guess?
				}
			} catch (Throwable t) {
				try {
					Closer.closeAll(sortedIds, bitY, bitZ, seqY, seqZ);
				} catch (Exception ex) {
					t.addSuppressed(ex);
				} catch (Throwable t2) {
					t2.addSuppressed(t);
					throw t2;
				}
				throw t;
			}
			Closer.closeAll(sortedIds, bitY, bitZ, seqY, seqZ);

		} catch (InterruptedException e) {
			throw new InterruptedIOException(e.getMessage());
		} catch (KWayMerger.KWayMergerException e) {
			throw new IOException(e);
		}
	}

	private final Sequence seqY, seqZ;
	private final Bitmap bitY, bitZ;
	private final AdjacencyList adjY, adjZ;
	private final TripleComponentOrder order;

	private BitmapTriplesIndexFile(Sequence seqY, Sequence seqZ, Bitmap bitY, Bitmap bitZ, TripleComponentOrder order) {
		this.seqY = seqY;
		this.seqZ = seqZ;
		this.bitY = bitY;
		this.bitZ = bitZ;
		this.order = order;

		this.adjY = new AdjacencyList(seqY, bitY);
		this.adjZ = new AdjacencyList(seqZ, bitZ);
	}

	@Override
	public Bitmap getBitmapY() {
		return bitY;
	}

	@Override
	public Bitmap getBitmapZ() {
		return bitZ;
	}

	@Override
	public Sequence getSeqY() {
		return seqY;
	}

	@Override
	public Sequence getSeqZ() {
		return seqZ;
	}

	@Override
	public AdjacencyList getAdjacencyListY() {
		return adjY;
	}

	@Override
	public AdjacencyList getAdjacencyListZ() {
		return adjZ;
	}

	@Override
	public TripleComponentOrder getOrder() {
		return order;
	}

	@Override
	public void close() throws IOException {
		Closer.closeAll(bitY, bitZ, seqY, seqZ);
	}
}
