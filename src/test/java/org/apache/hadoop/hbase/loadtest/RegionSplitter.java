package org.apache.hadoop.hbase.loadtest;

import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.LinkedList;
import java.util.Set;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

final class HashingSchemes
{
  public static final String SHA_1 = "SHA-1";
  public static final String SHA1 = "SHA1";
  public static final String MD5 = "MD5";
}


public class RegionSplitter {
  private static final Log LOG = LogFactory.getLog(RegionSplitter.class);

  private final static String MAXMD5 = "7FFFFFFF";
  private final static BigInteger MAXMD5_INT = new BigInteger(MAXMD5, 16);
  private final static int rowComparisonLength = MAXMD5.length();

  /**
   * Creates splits for the given hashingType.
   * @param hashingType
   * @param numberOfSplits
   * @return Byte array of size (numberOfSplits-1) corresponding to the
   * boundaries between splits.
   * @throws NoSuchAlgorithmException if the algorithm is not supported by
   * this splitter
   */
  public static byte[][] splitKeys(String hashingType, int numberOfSplits) {
    if (hashingType.equals(HashingSchemes.MD5)) {
      return splitKeysMD5(numberOfSplits);
    } else {
      throw new UnsupportedOperationException("This algorithm is not" +
      " currently supported by this class");
    }
  }

  /**
   * Creates splits for MD5 hashing.
   * @param numberOfSplits
   * @return Byte array of size (numberOfSplits-1) corresponding to the
   * boundaries between splits.
   */
  private static byte[][] splitKeysMD5(int numberOfSplits) {
    BigInteger[] bigIntegerSplits = split(MAXMD5_INT, numberOfSplits);
    byte[][] byteSplits = convertToBytes(bigIntegerSplits);
    return byteSplits;
  }

  /**
   * Splits the given BigInteger into numberOfSplits parts
   * @param maxValue
   * @param numberOfSplits
   * @return array of BigInteger which is of size (numberOfSplits-1)
   */
  private static BigInteger[] split(BigInteger maxValue, int numberOfSplits) {
    BigInteger[] splits = new BigInteger[numberOfSplits-1];
    BigInteger sizeOfEachSplit = maxValue.divide(BigInteger.
        valueOf(numberOfSplits));
    for (int i = 1; i < numberOfSplits; i++) {
      splits[i-1] = sizeOfEachSplit.multiply(BigInteger.valueOf(i));
    }
    return splits;
  }

  private static BigInteger split2(BigInteger minValue, BigInteger maxValue) {
    return maxValue.add(minValue).divide(BigInteger.valueOf(2));
  }

  /**
   * Returns an array of bytes corresponding to an array of BigIntegers
   * @param bigIntegers
   * @return bytes corresponding to the bigIntegers
   */
  private static byte[][] convertToBytes(BigInteger[] bigIntegers) {
    byte[][] returnBytes = new byte[bigIntegers.length][];
    for (int i = 0; i < bigIntegers.length; i++) {
      returnBytes[i] = convertToByte(bigIntegers[i]);
    }
    return returnBytes;
  }

  /**
   * Returns the bytes corresponding to the BigInteger
   * @param bigInteger
   * @return byte corresponding to input BigInteger
   */
  private static byte[] convertToByte(BigInteger bigInteger) {
    String bigIntegerString = bigInteger.toString(16);
    bigIntegerString = StringUtils.leftPad(bigIntegerString,
        rowComparisonLength, '0');
    return Bytes.toBytes(bigIntegerString);
  }

  /**
   * Returns the BigInteger represented by thebyte array
   * @param row
   * @return the corresponding BigInteger
   */
  private static BigInteger convertToBigInteger(byte[] row) {
    if (row.length > 0) {
      return new BigInteger(Bytes.toString(row), 16);
    } else {
      return BigInteger.ZERO;
    }
  }

  /////////////////////////////////////
  /**Code for hashing*/
  /////////////////////////////////////

  public static byte[] getHBaseKeyFromRowID(long rowID) {
    return getHBaseKeyFromEmail(rowID+"");
  }

  public static byte[] getHBaseKeyFromEmail(String email) {
    String ret = hashToString(hash(email));
    ret += ":" + email;
    return Bytes.toBytes(ret);
  }

  public static String hashToString(BigInteger data) {
    String ret = data.toString(16);
    return "00000000000000000000000000000000".substring(ret.length()) + ret;
  }

  public static BigInteger hash(String data)
  {
    byte[] result = hash(HashingSchemes.MD5, data.getBytes());
    BigInteger hash = new BigInteger(result);
    return hash.abs();
  }

  public static byte[] hash(String type, byte[] data)
  {
    byte[] result = null;
    try {
      MessageDigest messageDigest = MessageDigest.getInstance(type);
      result = messageDigest.digest(data);
    }
    catch (Exception e) {
      e.printStackTrace();
    }
    return result;
  }

  /**
   * main(): performs a BalancedSplit on an existing table
   * @param args table
   * @throws IOException HBase IO problem
   * @throws InterruptedException user requested exit
   */
  public static void main(String []args)
  throws IOException, InterruptedException {
    // input: tableName
    // TODO: add hashingType?
    if (1 != args.length) {
      System.err.println("Usage: RegionSplitter <TABLE>");
      return;
    }
    HTable table = new HTable(args[0]);

    // max outstanding split + associated compaction. default == 10% of servers
    final int MAX_OUTSTANDING = Math.max(table.getCurrentNrHRS() / 10, 2);

    Path hbDir = new Path(table.getConfiguration().get(HConstants.HBASE_DIR));
    Path tableDir = HTableDescriptor.getTableDir(hbDir, table.getTableName());
    Path splitFile = new Path(tableDir, "_balancedSplit");
    FileSystem fs = FileSystem.get(table.getConfiguration());

    // get a list of daughter regions to create
    Set<Pair<BigInteger, BigInteger>> daughterRegions = getSplits(args[0]);
    LinkedList<Pair<byte[],byte[]>> outstanding = Lists.newLinkedList();
    int splitCount = 0;
    final int origCount = daughterRegions.size();

    // open the split file and modify it as splits finish
    FSDataInputStream tmpIn = fs.open(splitFile);
    byte[] rawData = new byte[tmpIn.available()];
    tmpIn.readFully(rawData);
    tmpIn.close();
    FSDataOutputStream splitOut = fs.create(splitFile);
    splitOut.write(rawData);

    try {
      // *** split code ***
      for (Pair<BigInteger, BigInteger> dr : daughterRegions) {
        byte[] start = convertToByte(dr.getFirst());
        byte[] split = convertToByte(dr.getSecond());
        // request split
        LOG.debug("Splitting at " + Bytes.toString(split));
        byte[] sk = table.getRegionLocation(split).getRegionInfo().getStartKey();
        Preconditions.checkArgument(sk.length == 0 || Bytes.equals(start, sk));
        HBaseAdmin admin = new HBaseAdmin(table.getConfiguration());
        admin.split(table.getTableName(), split);

        // wait for one of the daughter regions to come online
        boolean daughterOnline = false;
        while (!daughterOnline) {
          LOG.debug("Waiting for daughter region to come online...");
          Thread.sleep(30 * 1000); // sleep
          table.clearRegionCache();
          HRegionInfo hri = table.getRegionLocation(split).getRegionInfo();
          daughterOnline = Bytes.equals(hri.getStartKey(), split)
                         && !hri.isOffline();
        }
        LOG.debug("Daughter region is online.");
        splitOut.writeChars("- " + dr.getFirst().toString(16) +
                            " " + dr.getSecond().toString(16) + "\n");
        splitCount++;
        if (splitCount % 10 == 0) {
          LOG.debug("STATUS UPDATE: " + splitCount + " / " + origCount);
        }

        // if we have too many outstanding splits, wait for oldest ones to finish
        outstanding.addLast(Pair.newPair(start, split));
        if (outstanding.size() > MAX_OUTSTANDING) {
          Pair<byte[], byte[]> reg = outstanding.removeFirst();
          String outStart= Bytes.toStringBinary(reg.getFirst());
          String outSplit = Bytes.toStringBinary(reg.getSecond());
          LOG.debug("Waiting for " + outStart + " " + outSplit +
                    " to finish compaction");
          // when a daughter region is opened, a compaction is triggered
          // wait until compaction completes for both daughter regions
          LinkedList<HRegionInfo> check = Lists.newLinkedList();
          // figure out where this region should be in HDFS
          check.add(table.getRegionLocation(reg.getFirst()).getRegionInfo());
          check.add(table.getRegionLocation(reg.getSecond()).getRegionInfo());
          while (!check.isEmpty()) {
            // compaction is completed when all reference files are gone
            for (HRegionInfo hri: check.toArray(new HRegionInfo[]{})) {
              boolean refFound = false;
              String startKey= Bytes.toStringBinary(hri.getStartKey());
              // check every Column Family for that region
              for (HColumnDescriptor c : hri.getTableDesc().getFamilies()) {
                Path cfDir = Store.getStoreHomedir(
                  tableDir, hri.getEncodedName(), c.getName());
                if (fs.exists(cfDir)) {
                  for (FileStatus file : fs.listStatus(cfDir)) {
                    refFound |= StoreFile.isReference(file.getPath());
                    if (refFound) {
                      LOG.debug("Reference still exists for " + startKey +
                                " at " + file.getPath());
                      break;
                    }
                  }
                }
                if (refFound) break;
              }
              if (!refFound) {
                check.remove(hri);
                LOG.debug("- finished compaction of " + startKey);
              }
            }
            // sleep in between requests
            if (!check.isEmpty()) {
              LOG.debug("Waiting for " + check.size() + " compactions");
              Thread.sleep(30 * 1000);
            }
          }
        }
      }
      LOG.debug("All regions have been split!");
    } finally {
      splitOut.close();
    }
    fs.delete(splitFile, false);
  }

  private static Set<Pair<BigInteger, BigInteger>> getSplits(String tblName)
  throws IOException {
    HTable table = new HTable(tblName);
    Path hbDir = new Path(table.getConfiguration().get(HConstants.HBASE_DIR));
    Path tableDir = HTableDescriptor.getTableDir(hbDir, table.getTableName());
    Path splitFile = new Path(tableDir, "_balancedSplit");
    FileSystem fs = FileSystem.get(table.getConfiguration());

    Set<Pair<BigInteger, BigInteger>> daughterRegions = Sets.newHashSet();

    // does a split file exist?
    if (!fs.exists(splitFile)) {
      // NO = fresh start. calculate splits to make
      LOG.debug("No _balancedSplit file.  Calculating splits...");

      // query meta for all regions in the table
      Set<Pair<BigInteger, BigInteger>> rows = Sets.newHashSet();
      Pair<byte[][],byte[][]> tmp = table.getStartEndKeys();
      byte[][] s = tmp.getFirst(), e = tmp.getSecond();
      Preconditions.checkArgument(s.length == e.length,
        "Start and End rows should be equivalent");

      // convert to the BigInteger format we used for original splits
      for (int i = 0; i < tmp.getFirst().length; ++i) {
        BigInteger start = convertToBigInteger(s[i]);
        BigInteger end = convertToBigInteger(e[i]);
        if (end == BigInteger.ZERO) {
          end = MAXMD5_INT;
        }
        rows.add(Pair.newPair(start, end));
      }
      LOG.debug("Table " + tblName + " has " + rows.size() +
                " regions that will be split.");

      // prepare the split file
      Path tmpFile = new Path(tableDir, "_balancedSplit_prepare");
      FSDataOutputStream tmpOut = fs.create(tmpFile);

      // calculate all the splits == [daughterRegions] = [(start, splitPoint)]
      for (Pair<BigInteger, BigInteger> r : rows) {
        BigInteger start = r.getFirst();
        BigInteger splitPoint = split2(r.getFirst(), r.getSecond());
        daughterRegions.add(Pair.newPair(start, splitPoint));
        LOG.debug("Will Split [" + r.getFirst().toString(16) + ", " +
          r.getSecond().toString(16) + ") at " + splitPoint.toString(16));
        tmpOut.writeChars("+ " + start.toString(16) +
                          " " + splitPoint.toString(16) + "\n");
      }
      tmpOut.close();
      fs.rename(tmpFile, splitFile);
    } else {
      LOG.debug("_balancedSplit file found. Replay log to restore state...");
      DistributedFileSystem dfs = (DistributedFileSystem)fs;
      dfs.recoverLease(splitFile);

      // parse split file and process remaining splits
      FSDataInputStream tmpIn = fs.open(splitFile);
      StringBuilder sb = new StringBuilder(tmpIn.available());
      while (tmpIn.available() > 0) {
        sb.append(tmpIn.readChar());
      }
      tmpIn.close();
      for (String line : sb.toString().split("\n")) {
        String[] cmd = line.split(" ");
        Preconditions.checkArgument(3 == cmd.length);
        BigInteger a = new BigInteger(cmd[1], 16);
        BigInteger b = new BigInteger(cmd[2], 16);
        Pair<BigInteger, BigInteger> r = Pair.newPair(a,b);
        if (cmd[0].equals("+")) {
          LOG.debug("Adding: " + a.toString(16) + "," + b.toString(16));
          daughterRegions.add(r);
        } else {
          LOG.debug("Removing: " + a.toString(16) + "," + b.toString(16));
          Preconditions.checkArgument(cmd[0].equals("-"),
                                      "Unknown option: " + cmd[0]);
          Preconditions.checkState(daughterRegions.contains(r),
                                   "Missing row: " + r);
          daughterRegions.remove(r);
        }
      }
      LOG.debug("Done reading. " + daughterRegions.size() + " regions left.");
    }
    return daughterRegions;
  }
}