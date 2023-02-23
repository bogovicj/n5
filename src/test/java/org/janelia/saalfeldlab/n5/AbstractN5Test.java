/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.n5;

import static org.junit.Assert.fail;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import org.janelia.saalfeldlab.n5.N5Reader.Version;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.reflect.TypeToken;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * Abstract base class for testing N5 functionality.
 * Subclasses are expected to provide a specific N5 implementation to be tested by defining the {@link #createN5Writer()} method.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 * @author Igor Pisarev &lt;pisarevi@janelia.hhmi.org&gt;
 * @author John Bogovic &lt;bogovicj@janelia.hhmi.org&gt;
 * @author Caleb Hulbert &lt;hulbertc@janelia.hhmi.org&gt;
 */
public abstract class AbstractN5Test {

	static protected final String groupName = "/test/group";
	static protected final String[] subGroupNames = new String[]{"a", "b", "c"};
	static protected final String datasetName = "/test/group/dataset";
	static protected final long[] dimensions = new long[]{100, 200, 300};
	static protected final int[] blockSize = new int[]{44, 33, 22};
	static protected final int blockNumElements = 44 * 33 * 22;

	static protected byte[] byteBlock;
	static protected short[] shortBlock;
	static protected int[] intBlock;
	static protected long[] longBlock;
	static protected float[] floatBlock;
	static protected double[] doubleBlock;

	static protected N5Writer n5;

	protected abstract N5Writer createN5Writer() throws IOException;

	protected abstract N5Writer createN5Writer(String location) throws IOException;

	protected abstract N5Reader createN5Reader(String location) throws IOException;

	protected Compression[] getCompressions() {

		return new Compression[] {
				new RawCompression(),
				new Bzip2Compression(),
				new GzipCompression(),
				new GzipCompression(5, true),
				new Lz4Compression(),
				new XzCompression()
			};
	}

	/**
	 * @throws IOException
	 */
	@Before
	public void setUpOnce() throws IOException {

		if (n5 != null)
			return;

		n5 = createN5Writer();

		final Random rnd = new Random();
		byteBlock = new byte[blockSize[0] * blockSize[1] * blockSize[2]];
		shortBlock = new short[blockSize[0] * blockSize[1] * blockSize[2]];
		intBlock = new int[blockSize[0] * blockSize[1] * blockSize[2]];
		longBlock = new long[blockSize[0] * blockSize[1] * blockSize[2]];
		floatBlock = new float[blockSize[0] * blockSize[1] * blockSize[2]];
		doubleBlock = new double[blockSize[0] * blockSize[1] * blockSize[2]];
		rnd.nextBytes(byteBlock);
		for(int i = 0; i < floatBlock.length; ++i) {
			shortBlock[i] = (short)rnd.nextInt();
			intBlock[i] = rnd.nextInt();
			longBlock[i] = rnd.nextLong();
			floatBlock[i] = Float.intBitsToFloat(rnd.nextInt());
			doubleBlock[i] = Double.longBitsToDouble(rnd.nextLong());
		}
	}

	/**
	 * @throws IOException
	 */
	@AfterClass
	public static void rampDownAfterClass() throws IOException {

		if (n5 != null) {
			Assert.assertTrue(n5.remove());
			n5 = null;
		}
	}

	@Test
	public void testCreateGroup() {

		try {
			n5.createGroup(groupName);
		} catch (final IOException e) {
			fail(e.getMessage());
		}

		final Path groupPath = Paths.get(groupName);
		for (int i = 0; i < groupPath.getNameCount(); ++i)
			if (!n5.exists(groupPath.subpath(0, i + 1).toString()))
				fail("Group does not exist");
	}

	@Test
	public void testCreateDataset() throws IOException {

		final DatasetAttributes info;
		try (N5Writer writer = createN5Writer()) {
			writer.createDataset(datasetName, dimensions, blockSize, DataType.UINT64, new RawCompression());

			if (!writer.exists(datasetName))
				fail("Dataset does not exist");

			info = writer.getDatasetAttributes(datasetName);
		}
		Assert.assertArrayEquals(dimensions, info.getDimensions());
		Assert.assertArrayEquals(blockSize, info.getBlockSize());
		Assert.assertEquals(DataType.UINT64, info.getDataType());
		Assert.assertTrue(info.getCompression() instanceof RawCompression);
	}

	@Test
	public void testWriteReadByteBlock() {

		for (final Compression compression : getCompressions()) {
			for (final DataType dataType : new DataType[]{
					DataType.UINT8,
					DataType.INT8}) {

				System.out.println("Testing " + compression.getType() + " " + dataType);
				try {
					n5.createDataset(datasetName, dimensions, blockSize, dataType, compression);
					final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
					final ByteArrayDataBlock dataBlock = new ByteArrayDataBlock(blockSize, new long[]{0, 0, 0}, byteBlock);
					n5.writeBlock(datasetName, attributes, dataBlock);

					final DataBlock<?> loadedDataBlock = n5.readBlock(datasetName, attributes, new long[]{0, 0, 0});

					Assert.assertArrayEquals(byteBlock, (byte[])loadedDataBlock.getData());

					Assert.assertTrue(n5.remove(datasetName));

				} catch (final IOException e) {
					e.printStackTrace();
					fail("Block cannot be written.");
				}
			}
		}
	}

	@Test
	public void testWriteReadShortBlock() {

		for (final Compression compression : getCompressions()) {
			for (final DataType dataType : new DataType[]{
					DataType.UINT16,
					DataType.INT16}) {

				System.out.println("Testing " + compression.getType() + " " + dataType);
				try {
					n5.createDataset(datasetName, dimensions, blockSize, dataType, compression);
					final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
					final ShortArrayDataBlock dataBlock = new ShortArrayDataBlock(blockSize, new long[]{0, 0, 0}, shortBlock);
					n5.writeBlock(datasetName, attributes, dataBlock);

					final DataBlock<?> loadedDataBlock = n5.readBlock(datasetName, attributes, new long[]{0, 0, 0});

					Assert.assertArrayEquals(shortBlock, (short[])loadedDataBlock.getData());

					Assert.assertTrue(n5.remove(datasetName));

				} catch (final IOException e) {
					e.printStackTrace();
					fail("Block cannot be written.");
				}
			}
		}
	}

	@Test
	public void testWriteReadIntBlock() {

		for (final Compression compression : getCompressions()) {
			for (final DataType dataType : new DataType[]{
					DataType.UINT32,
					DataType.INT32}) {

				System.out.println("Testing " + compression.getType() + " " + dataType);
				try {
					n5.createDataset(datasetName, dimensions, blockSize, dataType, compression);
					final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
					final IntArrayDataBlock dataBlock = new IntArrayDataBlock(blockSize, new long[]{0, 0, 0}, intBlock);
					n5.writeBlock(datasetName, attributes, dataBlock);

					final DataBlock<?> loadedDataBlock = n5.readBlock(datasetName, attributes, new long[]{0, 0, 0});

					Assert.assertArrayEquals(intBlock, (int[])loadedDataBlock.getData());

					Assert.assertTrue(n5.remove(datasetName));

				} catch (final IOException e) {
					e.printStackTrace();
					fail("Block cannot be written.");
				}
			}
		}
	}

	@Test
	public void testWriteReadLongBlock() {

		for (final Compression compression : getCompressions()) {
			for (final DataType dataType : new DataType[]{
					DataType.UINT64,
					DataType.INT64}) {

				System.out.println("Testing " + compression.getType() + " " + dataType);
				try {
					n5.createDataset(datasetName, dimensions, blockSize, dataType, compression);
					final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
					final LongArrayDataBlock dataBlock = new LongArrayDataBlock(blockSize, new long[]{0, 0, 0}, longBlock);
					n5.writeBlock(datasetName, attributes, dataBlock);

					final DataBlock<?> loadedDataBlock = n5.readBlock(datasetName, attributes, new long[]{0, 0, 0});

					Assert.assertArrayEquals(longBlock, (long[])loadedDataBlock.getData());

					Assert.assertTrue(n5.remove(datasetName));

				} catch (final IOException e) {
					e.printStackTrace();
					fail("Block cannot be written.");
				}
			}
		}
	}

	@Test
	public void testWriteReadFloatBlock() {

		for (final Compression compression : getCompressions()) {
			System.out.println("Testing " + compression.getType() + " float32");
			try {
				n5.createDataset(datasetName, dimensions, blockSize, DataType.FLOAT32, compression);
				final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
				final FloatArrayDataBlock dataBlock = new FloatArrayDataBlock(blockSize, new long[]{0, 0, 0}, floatBlock);
				n5.writeBlock(datasetName, attributes, dataBlock);

				final DataBlock<?> loadedDataBlock = n5.readBlock(datasetName, attributes, new long[]{0, 0, 0});

				Assert.assertArrayEquals(floatBlock, (float[])loadedDataBlock.getData(), 0.001f);

				Assert.assertTrue(n5.remove(datasetName));

			} catch (final IOException e) {
				e.printStackTrace();
				fail("Block cannot be written.");
			}
		}
	}


	@Test
	public void testWriteReadDoubleBlock() {

		for (final Compression compression : getCompressions()) {
			System.out.println("Testing " + compression.getType() + " float64");
			try {
				n5.createDataset(datasetName, dimensions, blockSize, DataType.FLOAT64, compression);
				final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
				final DoubleArrayDataBlock dataBlock = new DoubleArrayDataBlock(blockSize, new long[]{0, 0, 0}, doubleBlock);
				n5.writeBlock(datasetName, attributes, dataBlock);

				final DataBlock<?> loadedDataBlock = n5.readBlock(datasetName, attributes, new long[]{0, 0, 0});

				Assert.assertArrayEquals(doubleBlock, (double[])loadedDataBlock.getData(), 0.001);

				Assert.assertTrue(n5.remove(datasetName));

			} catch (final IOException e) {
				e.printStackTrace();
				fail("Block cannot be written.");
			}
		}
	}

	@Test
	public void testMode1WriteReadByteBlock() {

		final int[] differentBlockSize = new int[] {5, 10, 15};

		for (final Compression compression : getCompressions()) {
			for (final DataType dataType : new DataType[]{
					DataType.UINT8,
					DataType.INT8}) {

				System.out.println("Testing " + compression.getType() + " " + dataType + " (mode=1)");
				try {
					n5.createDataset(datasetName, dimensions, differentBlockSize, dataType, compression);
					final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
					final ByteArrayDataBlock dataBlock = new ByteArrayDataBlock(differentBlockSize, new long[]{0, 0, 0}, byteBlock);
					n5.writeBlock(datasetName, attributes, dataBlock);

					final DataBlock<?> loadedDataBlock = n5.readBlock(datasetName, attributes, new long[]{0, 0, 0});

					Assert.assertArrayEquals(byteBlock, (byte[])loadedDataBlock.getData());

					Assert.assertTrue(n5.remove(datasetName));

				} catch (final IOException e) {
					e.printStackTrace();
					fail("Block cannot be written.");
				}
			}
		}
	}

	@Test
	public void testWriteReadSerializableBlock() throws ClassNotFoundException {

		for (final Compression compression : getCompressions()) {

			final DataType dataType = DataType.OBJECT;
			System.out.println("Testing " + compression.getType() + " " + dataType + " (mode=2)");
			try {
				n5.createDataset(datasetName, dimensions, blockSize, dataType, compression);
				final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);

				final HashMap<String, ArrayList<double[]>> object = new HashMap<>();
				object.put("one", new ArrayList<>());
				object.put("two", new ArrayList<>());
				object.get("one").add(new double[] {1, 2, 3});
				object.get("two").add(new double[] {4, 5, 6, 7, 8});

				n5.writeSerializedBlock(object, datasetName, attributes, new long[]{0, 0, 0});

				final HashMap<String, ArrayList<double[]>> loadedObject = n5.readSerializedBlock(datasetName, attributes, new long[]{0, 0, 0});

				object.entrySet().stream().forEach(e -> Assert.assertArrayEquals(e.getValue().get(0), loadedObject.get(e.getKey()).get(0), 0.01));

				Assert.assertTrue(n5.remove(datasetName));

			} catch (final IOException e) {
				e.printStackTrace();
				fail("Block cannot be written.");
			}
		}
	}

	@Test
	public void testOverwriteBlock() {

		try {
			n5.createDataset(datasetName, dimensions, blockSize, DataType.INT32, new GzipCompression());
			final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);

			final IntArrayDataBlock randomDataBlock = new IntArrayDataBlock(blockSize, new long[]{0, 0, 0}, intBlock);
			n5.writeBlock(datasetName, attributes, randomDataBlock);
			final DataBlock<?> loadedRandomDataBlock = n5.readBlock(datasetName, attributes, new long[]{0, 0, 0});
			Assert.assertArrayEquals(intBlock, (int[])loadedRandomDataBlock.getData());

			// test the case where the resulting file becomes shorter
			final IntArrayDataBlock emptyDataBlock = new IntArrayDataBlock(blockSize, new long[]{0, 0, 0}, new int[DataBlock.getNumElements(blockSize)]);
			n5.writeBlock(datasetName, attributes, emptyDataBlock);
			final DataBlock<?> loadedEmptyDataBlock = n5.readBlock(datasetName, attributes, new long[]{0, 0, 0});
			Assert.assertArrayEquals(new int[DataBlock.getNumElements(blockSize)], (int[])loadedEmptyDataBlock.getData());

			Assert.assertTrue(n5.remove(datasetName));

		} catch (final IOException e) {
			e.printStackTrace();
			fail("Block cannot be written.");
		}
	}

	@Test
	public void testAttributes() {

		try {
			n5.createGroup(groupName);

			n5.setAttribute(groupName, "key1", "value1");
			Assert.assertEquals(1, n5.listAttributes(groupName).size());

			/* class interface */
			Assert.assertEquals("value1", n5.getAttribute(groupName, "key1", String.class));
			/* type interface */
			Assert.assertEquals("value1", n5.getAttribute(groupName, "key1", new TypeToken<String>(){}.getType()));

			final Map<String, String> newAttributes = new HashMap<>();
			newAttributes.put("key2", "value2");
			newAttributes.put("key3", "value3");
			n5.setAttributes(groupName, newAttributes);
			Assert.assertEquals(3, n5.listAttributes(groupName).size());
			/* class interface */
			Assert.assertEquals("value1", n5.getAttribute(groupName, "key1", String.class));
			Assert.assertEquals("value2", n5.getAttribute(groupName, "key2", String.class));
			Assert.assertEquals("value3", n5.getAttribute(groupName, "key3", String.class));
			/* type interface */
			Assert.assertEquals("value1", n5.getAttribute(groupName, "key1", new TypeToken<String>(){}.getType()));
			Assert.assertEquals("value2", n5.getAttribute(groupName, "key2", new TypeToken<String>(){}.getType()));
			Assert.assertEquals("value3", n5.getAttribute(groupName, "key3", new TypeToken<String>(){}.getType()));

			// test the case where the resulting file becomes shorter
			n5.setAttribute(groupName, "key1", new Integer(1));
			n5.setAttribute(groupName, "key2", new Integer(2));
			Assert.assertEquals(3, n5.listAttributes(groupName).size());
			/* class interface */
			Assert.assertEquals(new Integer(1), n5.getAttribute(groupName, "key1", Integer.class));
			Assert.assertEquals(new Integer(2), n5.getAttribute(groupName, "key2", Integer.class));
			Assert.assertEquals("value3", n5.getAttribute(groupName, "key3", String.class));
			/* type interface */
			Assert.assertEquals(new Integer(1), n5.getAttribute(groupName, "key1", new TypeToken<Integer>(){}.getType()));
			Assert.assertEquals(new Integer(2), n5.getAttribute(groupName, "key2", new TypeToken<Integer>(){}.getType()));
			Assert.assertEquals("value3", n5.getAttribute(groupName, "key3", new TypeToken<String>(){}.getType()));

		} catch (final IOException e) {
			fail(e.getMessage());
		}
	}

	@Test
	public void testRemove() {

		try {
			n5.createDataset(datasetName, dimensions, blockSize, DataType.UINT64, new RawCompression());
			n5.remove(groupName);
		} catch (final IOException e) {
			fail(e.getMessage());
		}

		if (n5.exists(groupName))
			fail("Group still exists");
	}

	@Test
	public void testList() {

		try( final N5Writer listN5 = createN5Writer()) {
			listN5.createGroup(groupName);
			for (final String subGroup : subGroupNames)
				listN5.createGroup(groupName + "/" + subGroup);

			final String[] groupsList = listN5.list(groupName);
			Arrays.sort(groupsList);

			Assert.assertArrayEquals(subGroupNames, groupsList);

			// test listing the root group ("" and "/" should give identical results)
			Assert.assertArrayEquals(new String[] {"test"}, listN5.list(""));
			Assert.assertArrayEquals(new String[] {"test"}, listN5.list("/"));


		} catch (final IOException e) {
			fail(e.getMessage());
		}
	}

	@Test
	public void testDeepList() throws IOException, ExecutionException, InterruptedException {

		final File tmpFile = Files.createTempDirectory("deeplist-test-").toFile();
		tmpFile.deleteOnExit();
		final String canonicalPath = tmpFile.getCanonicalPath();
		try (final N5Writer writer = createN5Writer(canonicalPath)) {

			writer.createGroup(groupName);
			for (final String subGroup : subGroupNames)
				writer.createGroup(groupName + "/" + subGroup);

			final List<String> groupsList = Arrays.asList(writer.deepList("/"));
			for (final String subGroup : subGroupNames)
				Assert.assertTrue("deepList contents", groupsList.contains(groupName.replaceFirst("/", "") + "/" + subGroup));

			for (final String subGroup : subGroupNames)
				Assert.assertTrue("deepList contents", Arrays.asList(writer.deepList("")).contains(groupName.replaceFirst("/", "") + "/" + subGroup));

			final DatasetAttributes datasetAttributes = new DatasetAttributes(dimensions, blockSize, DataType.UINT64, new RawCompression());
			final LongArrayDataBlock dataBlock = new LongArrayDataBlock(blockSize, new long[]{0, 0, 0}, new long[blockNumElements]);
			writer.createDataset(datasetName, datasetAttributes);
			writer.writeBlock(datasetName, datasetAttributes, dataBlock);

			final List<String> datasetList = Arrays.asList(writer.deepList("/"));
			for (final String subGroup : subGroupNames)
				Assert.assertTrue("deepList contents", datasetList.contains(groupName.replaceFirst("/", "") + "/" + subGroup));
			Assert.assertFalse("deepList stops at datasets", datasetList.contains(datasetName + "/0"));

			final List<String> datasetList2 = Arrays.asList(writer.deepList(""));
			for (final String subGroup : subGroupNames)
				Assert.assertTrue("deepList contents", datasetList2.contains(groupName.replaceFirst("/", "") + "/" + subGroup));
			Assert.assertTrue("deepList contents", datasetList2.contains(datasetName.replaceFirst("/", "")));
			Assert.assertFalse("deepList stops at datasets", datasetList2.contains(datasetName + "/0"));

			final String prefix = "/test";
			final String datasetSuffix = "group/dataset";
			final List<String> datasetList3 = Arrays.asList(writer.deepList(prefix));
			for (final String subGroup : subGroupNames)
				Assert.assertTrue("deepList contents", datasetList3.contains("group/" + subGroup));
			Assert.assertTrue("deepList contents", datasetList3.contains(datasetName.replaceFirst(prefix + "/", "")));

			// parallel deepList tests
			final List<String> datasetListP = Arrays.asList(writer.deepList("/", Executors.newFixedThreadPool(2)));
			for (final String subGroup : subGroupNames)
				Assert.assertTrue("deepList contents", datasetListP.contains(groupName.replaceFirst("/", "") + "/" + subGroup));
			Assert.assertTrue("deepList contents", datasetListP.contains(datasetName.replaceFirst("/", "")));
			Assert.assertFalse("deepList stops at datasets", datasetListP.contains(datasetName + "/0"));

			final List<String> datasetListP2 = Arrays.asList(writer.deepList("", Executors.newFixedThreadPool(2)));
			for (final String subGroup : subGroupNames)
				Assert.assertTrue("deepList contents", datasetListP2.contains(groupName.replaceFirst("/", "") + "/" + subGroup));
			Assert.assertTrue("deepList contents", datasetListP2.contains(datasetName.replaceFirst("/", "")));
			Assert.assertFalse("deepList stops at datasets", datasetListP2.contains(datasetName + "/0"));

			final List<String> datasetListP3 = Arrays.asList(writer.deepList(prefix, Executors.newFixedThreadPool(2)));
			for (final String subGroup : subGroupNames)
				Assert.assertTrue("deepList contents", datasetListP3.contains("group/" + subGroup));
			Assert.assertTrue("deepList contents", datasetListP3.contains(datasetName.replaceFirst(prefix + "/", "")));
			Assert.assertFalse("deepList stops at datasets", datasetListP3.contains(datasetName + "/0"));

			// test filtering
			final Predicate<String> isCalledDataset = d -> {
				return d.endsWith("/dataset");
			};
			final Predicate<String> isBorC = d -> {
				return d.matches(".*/[bc]$");
			};

			final List<String> datasetListFilter1 = Arrays.asList(writer.deepList(prefix, isCalledDataset));
			Assert.assertTrue(
					"deepList filter \"dataset\"",
					datasetListFilter1.stream().map(x -> prefix + x).allMatch(isCalledDataset));

			final List<String> datasetListFilter2 = Arrays.asList(writer.deepList(prefix, isBorC));
			Assert.assertTrue(
					"deepList filter \"b or c\"",
					datasetListFilter2.stream().map(x -> prefix + x).allMatch(isBorC));

			final List<String> datasetListFilterP1 =
					Arrays.asList(writer.deepList(prefix, isCalledDataset, Executors.newFixedThreadPool(2)));
			Assert.assertTrue(
					"deepList filter \"dataset\"",
					datasetListFilterP1.stream().map(x -> prefix + x).allMatch(isCalledDataset));

			final List<String> datasetListFilterP2 =
					Arrays.asList(writer.deepList(prefix, isBorC, Executors.newFixedThreadPool(2)));
			Assert.assertTrue(
					"deepList filter \"b or c\"",
					datasetListFilterP2.stream().map(x -> prefix + x).allMatch(isBorC));

			// test dataset filtering
			final List<String> datasetListFilterD = Arrays.asList(writer.deepListDatasets(prefix));
			Assert.assertTrue(
					"deepListDataset",
					datasetListFilterD.size() == 1 && (prefix + "/" + datasetListFilterD.get(0)).equals(datasetName));
			Assert.assertArrayEquals(
					datasetListFilterD.toArray(),
					writer.deepList(
							prefix,
							a -> {
								try { return writer.datasetExists(a); }
								catch (final IOException e) { return false; }
							}));

			final List<String> datasetListFilterDandBC = Arrays.asList(writer.deepListDatasets(prefix, isBorC));
			Assert.assertTrue("deepListDatasetFilter", datasetListFilterDandBC.size() == 0);
			Assert.assertArrayEquals(
					datasetListFilterDandBC.toArray(),
					writer.deepList(
							prefix,
							a -> {
								try { return writer.datasetExists(a) && isBorC.test(a); }
								catch (final IOException e) { return false; }
							}));

			final List<String> datasetListFilterDP =
					Arrays.asList(writer.deepListDatasets(prefix, Executors.newFixedThreadPool(2)));
			Assert.assertTrue(
					"deepListDataset Parallel",
					datasetListFilterDP.size() == 1 && (prefix + "/" + datasetListFilterDP.get(0)).equals(datasetName));
			Assert.assertArrayEquals(
					datasetListFilterDP.toArray(),
					writer.deepList(
							prefix,
							a -> {
								try { return writer.datasetExists(a); }
								catch (final IOException e) { return false; }
							},
							Executors.newFixedThreadPool(2)));

			final List<String> datasetListFilterDandBCP =
					Arrays.asList(writer.deepListDatasets(prefix, isBorC, Executors.newFixedThreadPool(2)));
			Assert.assertTrue("deepListDatasetFilter Parallel", datasetListFilterDandBCP.size() == 0);
			Assert.assertArrayEquals(
					datasetListFilterDandBCP.toArray(),
					writer.deepList(
							prefix,
							a -> {
								try { return writer.datasetExists(a) && isBorC.test(a); }
								catch (final IOException e) { return false; }
							},
							Executors.newFixedThreadPool(2)));
		}
	}

	@Test
	public void testExists() {

		final String groupName2 = groupName + "-2";
		final String datasetName2 = datasetName + "-2";
		final String notExists = groupName + "-notexists";
		try {
			n5.createDataset(datasetName2, dimensions, blockSize, DataType.UINT64, new RawCompression());
			Assert.assertTrue(n5.exists(datasetName2));
			Assert.assertTrue(n5.datasetExists(datasetName2));

			n5.createGroup(groupName2);
			Assert.assertTrue(n5.exists(groupName2));
			Assert.assertFalse(n5.datasetExists(groupName2));

			Assert.assertFalse(n5.exists(notExists));
			Assert.assertFalse(n5.datasetExists(notExists));
		} catch (final IOException e) {
			fail(e.getMessage());
		}
	}

	@Test
	public void testListAttributes() {

		final String groupName2 = groupName + "-2";
		final String datasetName2 = datasetName + "-2";
		try {
			n5.createDataset(datasetName2, dimensions, blockSize, DataType.UINT64, new RawCompression());
			n5.setAttribute(datasetName2, "attr1", new double[] {1.1, 2.1, 3.1});
			n5.setAttribute(datasetName2, "attr2", new String[] {"a", "b", "c"});
			n5.setAttribute(datasetName2, "attr3", 1.1);
			n5.setAttribute(datasetName2, "attr4", "a");
			n5.setAttribute(datasetName2, "attr5", new long[] {1, 2, 3});
			n5.setAttribute(datasetName2, "attr6", 1);
			n5.setAttribute(datasetName2, "attr7", new double[] {1, 2, 3.1});
			n5.setAttribute(datasetName2, "attr8", new Object[] {"1", 2, 3.1});

			Map<String, Class<?>> attributesMap = n5.listAttributes(datasetName2);
			Assert.assertTrue(attributesMap.get("attr1") == double[].class);
			Assert.assertTrue(attributesMap.get("attr2") == String[].class);
			Assert.assertTrue(attributesMap.get("attr3") == double.class);
			Assert.assertTrue(attributesMap.get("attr4") == String.class);
			Assert.assertTrue(attributesMap.get("attr5") == long[].class);
			Assert.assertTrue(attributesMap.get("attr6") == long.class);
			Assert.assertTrue(attributesMap.get("attr7") == double[].class);
			Assert.assertTrue(attributesMap.get("attr8") == Object[].class);

			n5.createGroup(groupName2);
			n5.setAttribute(groupName2, "attr1", new double[] {1.1, 2.1, 3.1});
			n5.setAttribute(groupName2, "attr2", new String[] {"a", "b", "c"});
			n5.setAttribute(groupName2, "attr3", 1.1);
			n5.setAttribute(groupName2, "attr4", "a");
			n5.setAttribute(groupName2, "attr5", new long[] {1, 2, 3});
			n5.setAttribute(groupName2, "attr6", 1);
			n5.setAttribute(groupName2, "attr7", new double[] {1, 2, 3.1});
			n5.setAttribute(groupName2, "attr8", new Object[] {"1", 2, 3.1});

			attributesMap = n5.listAttributes(groupName2);
			Assert.assertTrue(attributesMap.get("attr1") == double[].class);
			Assert.assertTrue(attributesMap.get("attr2") == String[].class);
			Assert.assertTrue(attributesMap.get("attr3") == double.class);
			Assert.assertTrue(attributesMap.get("attr4") == String.class);
			Assert.assertTrue(attributesMap.get("attr5") == long[].class);
			Assert.assertTrue(attributesMap.get("attr6") == long.class);
			Assert.assertTrue(attributesMap.get("attr7") == double[].class);
			Assert.assertTrue(attributesMap.get("attr8") == Object[].class);
		} catch (final IOException e) {
			fail(e.getMessage());
		}
	}

	@Test
	public void testVersion() throws NumberFormatException, IOException {

		final Version n5Version = n5.getVersion();

		Assert.assertTrue(n5Version.equals(N5Reader.VERSION));

		final File tmpFile = Files.createTempDirectory("version-test-").toFile();
		tmpFile.deleteOnExit();
		final String canonicalPath = tmpFile.getCanonicalPath();
		try (N5Writer writer = createN5Writer(canonicalPath)) {
			writer.setAttribute("/", N5Reader.VERSION_KEY, new Version(N5Reader.VERSION.getMajor() + 1, N5Reader.VERSION.getMinor(), N5Reader.VERSION.getPatch()).toString());
			Assert.assertFalse(N5Reader.VERSION.isCompatible(writer.getVersion()));
		}
	}

	@Test
	public void testReaderCreation() throws IOException
	{
		final File tmpFile = Files.createTempDirectory("reader-create-test-").toFile();
		tmpFile.deleteOnExit();
		final String canonicalPath = tmpFile.getCanonicalPath();
		try (N5Writer writer = createN5Writer(canonicalPath)) {

			final N5Reader n5r = createN5Reader( canonicalPath );
			assertNotNull( n5r );

			// existing directory without attributes is okay
			writer.createGroup( "noAttrs" );
			final N5Reader na = createN5Reader( canonicalPath + "/noAttrs" );
			assertNotNull( na );

			// existing directory without attributes is okay
			writer.createGroup( "withAttrs" );
			writer.setAttribute( "withAttrs", "mystring", "ms" );
			final N5Reader wa = createN5Reader( canonicalPath + "/withAttrs" );
			assertNotNull( wa );

			// non-existent directory should fail
			assertThrows( "Non-existant directory throws error", IOException.class,
					() -> {
						createN5Reader( canonicalPath + "/nonExistant" );
					} );

			// existing directory with incompatible version should fail
			writer.setAttribute("/", N5Reader.VERSION_KEY, new Version(N5Reader.VERSION.getMajor() + 1, N5Reader.VERSION.getMinor(), N5Reader.VERSION.getPatch()).toString());
			assertThrows( "Incompatible version throws error", IOException.class,
					() -> {
						createN5Reader(canonicalPath);
					} );
		}
	}

	@Test
	public void testDelete() throws IOException {
		final String datasetName = AbstractN5Test.datasetName + "-test-delete";
		n5.createDataset(datasetName, dimensions, blockSize, DataType.UINT8, new RawCompression());
		final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
		final long[] position1 = {0, 0, 0};
		final long[] position2 = {0, 1, 2};

		// no blocks should exist to begin with
		Assert.assertTrue(testDeleteIsBlockDeleted(n5.readBlock(datasetName, attributes, position1)));
		Assert.assertTrue(testDeleteIsBlockDeleted(n5.readBlock(datasetName, attributes, position2)));

		final ByteArrayDataBlock dataBlock = new ByteArrayDataBlock(blockSize, position1, byteBlock);
		n5.writeBlock(datasetName, attributes, dataBlock);

		// block should exist at position1 but not at position2
		final DataBlock<?> readBlock = n5.readBlock(datasetName, attributes, position1);
		Assert.assertNotNull(readBlock);
		Assert.assertTrue(readBlock instanceof ByteArrayDataBlock);
		Assert.assertArrayEquals(byteBlock, ((ByteArrayDataBlock) readBlock).getData());
		Assert.assertTrue(testDeleteIsBlockDeleted(n5.readBlock(datasetName, attributes, position2)));

		// deletion should report true in all cases
		Assert.assertTrue(n5.deleteBlock(datasetName, position1));
		Assert.assertTrue(n5.deleteBlock(datasetName, position1));
		Assert.assertTrue(n5.deleteBlock(datasetName, position2));

		// no block should exist anymore
		Assert.assertTrue(testDeleteIsBlockDeleted(n5.readBlock(datasetName, attributes, position1)));
		Assert.assertTrue(testDeleteIsBlockDeleted(n5.readBlock(datasetName, attributes, position2)));
	}

	protected boolean testDeleteIsBlockDeleted(final DataBlock<?> dataBlock) {
		return dataBlock == null;
	}

	public class TestData<T> {

		public String groupPath;
		public String attributePath;
		public T attributeValue;
		public Class<T> attributeClass;

		public TestData(String groupPath, String key, T attributeValue) {

			this.groupPath = groupPath;
			this.attributePath = key;
			this.attributeValue = attributeValue;
			this.attributeClass = (Class<T>)attributeValue.getClass();
		}
	}

	protected void addAndTest(ArrayList<TestData<?>> existingTests, TestData<?> testData) {
		/* test a new value on existing path */
		try {
			n5.setAttribute(testData.groupPath, testData.attributePath, testData.attributeValue);
			Assert.assertEquals(testData.attributeValue, n5.getAttribute(testData.groupPath, testData.attributePath, testData.attributeClass));
			Assert.assertEquals(testData.attributeValue,
					n5.getAttribute(testData.groupPath, testData.attributePath, TypeToken.get(testData.attributeClass).getType()));

			/* previous values should still be there, but we remove first if the test we just added overwrites. */
			existingTests.removeIf(test -> {
				try {
					final String normalizedTestKey = N5URL.from(null, "", test.attributePath).normalizeAttributePath().replaceAll("^/", "");
					final String normalizedTestDataKey = N5URL.from(null, "", testData.attributePath).normalizeAttributePath().replaceAll("^/", "");
					return normalizedTestKey.equals(normalizedTestDataKey);
				} catch (URISyntaxException e) {
					throw new RuntimeException(e);
				}
			});
			for (TestData<?> test : existingTests) {
				Assert.assertEquals(test.attributeValue, n5.getAttribute(test.groupPath, test.attributePath, test.attributeClass));
				Assert.assertEquals(test.attributeValue, n5.getAttribute(test.groupPath, test.attributePath, TypeToken.get(test.attributeClass).getType()));
			}
			existingTests.add(testData);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testAttributePaths() throws IOException {

		String testGroup = "test";
		n5.createGroup(testGroup);

		final ArrayList<TestData<?>> existingTests = new ArrayList<>();

		/* Test a new value by path */
		addAndTest(existingTests, new TestData<>(testGroup, "/a/b/c/key1", "value1"));
		/* test a new value on existing path */
		addAndTest(existingTests, new TestData<>(testGroup, "/a/b/key2", "value2"));
		/* test replacing an existing value */
		addAndTest(existingTests, new TestData<>(testGroup, "/a/b/c/key1", "new_value1"));

		/* Test a new value with arrays */
		addAndTest(existingTests, new TestData<>(testGroup, "/array[0]/b/c/key1", "array_value1"));
		/* test replacing an existing value */
		addAndTest(existingTests, new TestData<>(testGroup, "/array[0]/b/c/key1", "new_array_value1"));
		/* test a new value on existing path with arrays */
		addAndTest(existingTests, new TestData<>(testGroup, "/array[0]/d[3]/key2", "array_value2"));
		/* test a new value on existing path with nested arrays */
		addAndTest(existingTests, new TestData<>(testGroup, "/array[1][2]/[3]key2", "array2_value2"));
		/* test with syntax variants */
		addAndTest(existingTests, new TestData<>(testGroup, "/array[1][2]/[3]key2", "array3_value3"));
		addAndTest(existingTests, new TestData<>(testGroup, "array[1]/[2][3]/key2", "array3_value4"));
		addAndTest(existingTests, new TestData<>(testGroup, "/array/[1]/[2]/[3]/key2", "array3_value5"));
		/* test with whitespace*/
		addAndTest(existingTests, new TestData<>(testGroup, " ", "space"));
		addAndTest(existingTests, new TestData<>(testGroup, "\n", "newline"));
		addAndTest(existingTests, new TestData<>(testGroup, "\t", "tab"));
		addAndTest(existingTests, new TestData<>(testGroup, "\r\n", "windows_newline"));
		addAndTest(existingTests, new TestData<>(testGroup, " \n\t \t \n \r\n\r\n", "mixed"));
		/* test URI encoded characters inside square braces */
		addAndTest(existingTests, new TestData<>(testGroup, "[ ]", "space"));
		addAndTest(existingTests, new TestData<>(testGroup, "[\n]", "newline"));
		addAndTest(existingTests, new TestData<>(testGroup, "[\t]", "tab"));
		addAndTest(existingTests, new TestData<>(testGroup, "[\r\n]", "windows_newline"));
		addAndTest(existingTests, new TestData<>(testGroup, "[ ][\n][\t][ \t \n \r\n][\r\n]", "mixed"));
		addAndTest(existingTests, new TestData<>(testGroup, "[ ][\\n][\\t][ \\t \\n \\r\\n][\\r\\n]", "mixed"));
		addAndTest(existingTests, new TestData<>(testGroup, "[\\]", "backslash"));

		/* Non String tests */

		addAndTest(existingTests, new TestData<>(testGroup, "/an/integer/test", 1));
		addAndTest(existingTests, new TestData<>(testGroup, "/a/double/test", 1.0));
		addAndTest(existingTests, new TestData<>(testGroup, "/a/float/test", 1.0F));
		final TestData<Boolean> booleanTest = new TestData<>(testGroup, "/a/boolean/test", true);
		addAndTest(existingTests, booleanTest);

		/* overwrite structure*/
		existingTests.remove(booleanTest);
		addAndTest(existingTests, new TestData<>(testGroup, "/a/boolean[2]/test", true));

		/* Fill an array with number */
		addAndTest(existingTests, new TestData<>(testGroup, "/filled/double_array[5]", 5.0));
		addAndTest(existingTests, new TestData<>(testGroup, "/filled/double_array[1]", 1.0));
		addAndTest(existingTests, new TestData<>(testGroup, "/filled/double_array[2]", 2.0));

		addAndTest(existingTests, new TestData<>(testGroup, "/filled/double_array[4]", 4.0));
		addAndTest(existingTests, new TestData<>(testGroup, "/filled/double_array[0]", 0.0));

		/* We intentionally skipped index 3, it should be `0` */
		Assert.assertEquals((Integer)0, n5.getAttribute(testGroup, "/filled/double_array[3]", Integer.class));

		/* Fill an array with Object */
		addAndTest(existingTests, new TestData<>(testGroup, "/filled/string_array[5]", "f"));
		addAndTest(existingTests, new TestData<>(testGroup, "/filled/string_array[1]", "b"));
		addAndTest(existingTests, new TestData<>(testGroup, "/filled/string_array[2]", "c"));

		addAndTest(existingTests, new TestData<>(testGroup, "/filled/string_array[4]", "e"));
		addAndTest(existingTests, new TestData<>(testGroup, "/filled/string_array[0]", "a"));

		/* We intentionally skipped index 3, it should be null */
		Assert.assertNull( n5.getAttribute( testGroup, "/filled/double_array[3]", JsonNull.class ) );

		/* Lastly, we wish to ensure that escaping does NOT interpret the json path structure, but rather it adds the keys opaquely*/
		final HashMap<String, Object> testAttributes = new HashMap<>();
		testAttributes.put("\\/z\\/y\\/x", 10);
		testAttributes.put("q\\/r\\/t", 11);
		testAttributes.put("\\/l\\/m\\[10]\\/n", 12);
		testAttributes.put("\\/", 13);
		/* intentionally the same as above, but this time it should be added as an opaque key*/
		testAttributes.put("\\/a\\/b/key2", "value2");
		n5.setAttributes(testGroup, testAttributes);

		assertEquals((Integer)10, n5.getAttribute(testGroup, "\\/z\\/y\\/x", Integer.class));
		assertEquals((Integer)11, n5.getAttribute(testGroup, "q\\/r\\/t", Integer.class));
		assertEquals((Integer)12, n5.getAttribute(testGroup, "\\/l\\/m\\[10]\\/n", Integer.class));
		assertEquals((Integer)13, n5.getAttribute(testGroup, "\\/", Integer.class));

		/* We are passing a different type for the same key ("/").
		 * This means it will try ot grab the exact match first, but then fail, and continuat on
		 * to try and grab the value as a json structure. I should grab the root, and match the empty string case */
		assertEquals(n5.getAttribute(testGroup, "", JsonObject.class), n5.getAttribute(testGroup, "/", JsonObject.class));

		/* Lastly, ensure grabing nonsence results in an exception */
		assertNull(n5.getAttribute(testGroup, "/this/key/does/not/exist", Object.class));

		n5.remove(testGroup);
	}


	@Test
	public void testAttributePathEscaping() throws IOException {

		final JsonObject emptyObj = new JsonObject();
		final String empty = "{}";

		final String slashKey = "/";
		final String abcdefKey = "abc/def";
		final String zeroKey = "[0]";
		final String bracketsKey = "]] [] [[";
		final String doubleBracketsKey = "[[2][33]]";
		final String doubleBackslashKey = "\\\\\\\\"; //Evaluates to `\\` through java and json

		final String dataString = "dataString";
		final String rootSlash = jsonKeyVal( slashKey, dataString );
		final String abcdef = jsonKeyVal( abcdefKey, dataString );
		final String zero = jsonKeyVal( zeroKey, dataString );
		final String brackets = jsonKeyVal( bracketsKey, dataString );
		final String doubleBrackets = jsonKeyVal( doubleBracketsKey, dataString );
		final String doubleBackslash = jsonKeyVal( doubleBackslashKey, dataString );

		// "/" as key
		String grp = "a";
		n5.createGroup( grp );
		n5.setAttribute( grp, "\\/", dataString );
		assertEquals( dataString, n5.getAttribute( grp, "\\/", String.class ) );
		String jsonContents = readAttributesAsString( grp );
		assertEquals( rootSlash, jsonContents );

		// "abc/def" as key
		grp = "b";
		n5.createGroup( grp );
		n5.setAttribute( grp, "abc\\/def", dataString );
		assertEquals( dataString, n5.getAttribute( grp, "abc\\/def", String.class ) );
		jsonContents = readAttributesAsString( grp );
		assertEquals( abcdef, jsonContents );

		// "[0]"  as a key
		grp = "c";
		n5.createGroup( grp );
		n5.setAttribute( grp, "\\[0]", dataString );
		assertEquals( dataString, n5.getAttribute( grp, "\\[0]", String.class ) );
		jsonContents = readAttributesAsString( grp );
		assertEquals( zero, jsonContents );

		// "]] [] [["  as a key
		grp = "d";
		n5.createGroup( grp );
		n5.setAttribute( grp, bracketsKey, dataString );
		assertEquals( dataString, n5.getAttribute( grp, bracketsKey, String.class ) );
		jsonContents = readAttributesAsString( grp );
		assertEquals( brackets, jsonContents );

		// "[[2][33]]"  as a key FIXME: What to do about escaping inside square brackets?
		grp = "e";
		n5.createGroup( grp );
		n5.setAttribute( grp, "[\\[2]\\[33]]", dataString );
		assertEquals( dataString, n5.getAttribute( grp, "[\\[2]\\[33]]", String.class ) );
		jsonContents = readAttributesAsString( grp );
		assertEquals( doubleBrackets, jsonContents );

		// "\\" as key
		grp = "f";
		n5.createGroup( grp );
		n5.setAttribute( grp, "\\\\", dataString );
		assertEquals( dataString, n5.getAttribute( grp, "\\\\", String.class ) );
		jsonContents = readAttributesAsString( grp );
		assertEquals( doubleBackslash, jsonContents );

		// clear
		n5.setAttribute( grp, "/", emptyObj );
		jsonContents = readAttributesAsString( grp );
		assertEquals( empty, jsonContents );
	}

	/*
	 * For readability above
	 */
	private String jsonKeyVal( String key, String val ) {

		return String.format( "{\"%s\":\"%s\"}", key, val );
	}

	private String readAttributesAsString( final String group ) {

		final String basePath = ((N5FSWriter)n5).getBasePath();
		try
		{
			return new String( Files.readAllBytes( Paths.get( basePath, group, "attributes.json" )));
		} catch ( IOException e ) { }
		return null;
	}

	@Test
	public void testRootLeaves() throws IOException {

		/* Test with new root's each time */
		final ArrayList<TestData<?>> tests = new ArrayList<>();
		tests.add(new TestData<>(groupName, "", "empty_root"));
		tests.add(new TestData<>(groupName, "/", "replace_empty_root"));
		tests.add(new TestData<>(groupName, "[0]", "array_root"));

		for (TestData<?> testData : tests) {
			final File tmpFile = Files.createTempDirectory("root-leaf-test-").toFile();
			tmpFile.deleteOnExit();
			final String canonicalPath = tmpFile.getCanonicalPath();
			try (final N5Writer writer = createN5Writer(canonicalPath)) {
				writer.setAttribute(testData.groupPath, testData.attributePath, testData.attributeValue);
				Assert.assertEquals(testData.attributeValue, writer.getAttribute(testData.groupPath, testData.attributePath, testData.attributeClass));
				Assert.assertEquals(testData.attributeValue,
						writer.getAttribute(testData.groupPath, testData.attributePath, TypeToken.get(testData.attributeClass).getType()));
			}
		}

		/* Test with replacing an existing root-leaf */
		tests.clear();
		tests.add(new TestData<>(groupName, "", "empty_root"));
		tests.add(new TestData<>(groupName, "/", "replace_empty_root"));
		tests.add(new TestData<>(groupName, "[0]", "array_root"));

		final File tmpFile = Files.createTempDirectory("reuse-root-leaf-test-").toFile();
		tmpFile.deleteOnExit();
		final String canonicalPath = tmpFile.getCanonicalPath();
		try (final N5Writer writer = createN5Writer(canonicalPath)) {
			for (TestData<?> testData : tests) {
				writer.setAttribute(testData.groupPath, testData.attributePath, testData.attributeValue);
				Assert.assertEquals(testData.attributeValue, writer.getAttribute(testData.groupPath, testData.attributePath, testData.attributeClass));
				Assert.assertEquals(testData.attributeValue,
						writer.getAttribute(testData.groupPath, testData.attributePath, TypeToken.get(testData.attributeClass).getType()));
			}
		}

		/* Test with replacing an existing root non-leaf*/
		tests.clear();
		final TestData<Integer> rootAsObject = new TestData<>(groupName, "/some/non/leaf[3]/structure", 100);
		final TestData<Integer> rootAsPrimitive = new TestData<>(groupName, "", 200);
		final TestData<Integer> rootAsArray = new TestData<>(groupName, "/", 300);
		tests.add(rootAsPrimitive);
		tests.add(rootAsArray);
		final File tmpFile2 = Files.createTempDirectory("reuse-root-leaf-test-").toFile();
		tmpFile2.deleteOnExit();
		final String canonicalPath2 = tmpFile2.getCanonicalPath();
		try (final N5Writer writer = createN5Writer(canonicalPath2)) {
			for (TestData<?> test : tests) {
				/* Set the root as Object*/
				writer.setAttribute(rootAsObject.groupPath, rootAsObject.attributePath, rootAsObject.attributeValue);
				Assert.assertEquals(rootAsObject.attributeValue, writer.getAttribute(rootAsObject.groupPath, rootAsObject.attributePath, rootAsObject.attributeClass));

				/* Override the root with something else */
				writer.setAttribute(test.groupPath, test.attributePath, test.attributeValue);
				/* Verify original root is gone */
				Assert.assertNull(writer.getAttribute(rootAsObject.groupPath, rootAsObject.attributePath, rootAsObject.attributeClass));
				/* verify new root exists */
				Assert.assertEquals(test.attributeValue, writer.getAttribute(test.groupPath, test.attributePath, test.attributeClass));
			}
		}
	}
}
