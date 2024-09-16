/*
 * Copyright 2014 Edward Aftandilian. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.tufts.eaftan.hprofparser.parser;

import com.google.common.base.Preconditions;

import edu.tufts.eaftan.hprofparser.handler.RecordHandler;
import edu.tufts.eaftan.hprofparser.parser.datastructures.AllocSite;
import edu.tufts.eaftan.hprofparser.parser.datastructures.CPUSample;
import edu.tufts.eaftan.hprofparser.parser.datastructures.ClassInfo;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Constant;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Instance;
import edu.tufts.eaftan.hprofparser.parser.datastructures.InstanceField;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Static;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Type;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Value;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Parses an hprof heap dump file in binary format.  The hprof dump file format is documented in
 * the hprof_b_spec.h file in the hprof source, which is open-source and available from Oracle.
 */
public class HprofParser {

  private RecordHandler handler;
  private HashMap<Long, ClassInfo> classMap;

  public HprofParser(RecordHandler handler) {
    this.handler = handler;
    classMap = new HashMap<Long, ClassInfo>();
  } 

  public void parse(File file) throws IOException {

    /* The file format looks like this:
     *
     * header: 
     *   [u1]* - a null-terminated sequence of bytes representing the format
     *           name and version
     *   u4 - size of identifiers/pointers
     *   u4 - high number of word of number of milliseconds since 0:00 GMT, 
     *        1/1/70
     *   u4 - low number of word of number of milliseconds since 0:00 GMT, 
     *        1/1/70
     *
     * records:
     *   u1 - tag denoting the type of record
     *   u4 - number of microseconds since timestamp in header
     *   u4 - number of bytes that follow this field in this record
     *   [u1]* - body
     */

    String format;
    int idSize;
    long startTime;
    try (
      FileInputStream fs = new FileInputStream(file);
      DataInputStream in = new DataInputStream(new BufferedInputStream(fs));
    ) {
      // header
      format = readUntilNull(in);
      idSize = in.readInt();
      startTime = in.readLong();
      handler.header(format, idSize, startTime);

      // records
      boolean done;
      do {
        done = parseRecord(in, idSize, true);
      } while (!done);
    }

    try (
      FileInputStream fs = new FileInputStream(file);
      DataInputStream in = new DataInputStream(new BufferedInputStream(fs));
    ) {
      skipBytesSafe(in, format.length() + 1 + 4 + 8); // skip header (1 for null terminator)
      boolean done;
      do {
        done = parseRecord(in, idSize, false);
      } while (!done);
    }

    handler.finished();
  }

  public static String readUntilNull(DataInput in) throws IOException {

    int bytesRead = 0;
    byte[] bytes = new byte[25];

    while ((bytes[bytesRead] = in.readByte()) != 0) {
      bytesRead++;
      if (bytesRead >= bytes.length) {
        byte[] newBytes = new byte[bytesRead + 20];
        for (int i=0; i<bytes.length; i++) {
          newBytes[i] = bytes[i];
        }
        bytes = newBytes;
      }
    }
    return new String(bytes, 0, bytesRead);
  }

  /**
   * @return true if there are no more records to parse
   */
  private boolean parseRecord(DataInput in, int idSize, boolean isFirstPass) throws IOException {

    /* format:
     *   u1 - tag
     *   u4 - time
     *   u4 - length
     *   [u1]* - body
     */

    // if we get an EOF on this read, it just means we're done
    byte tag;
    try {
      tag = in.readByte();
    } catch (EOFException e) {
      return true;
    }
    
    // otherwise propagate the EOFException
    //int time = in.readInt();    // TODO(eaftan): we might want time passed to handler fns
    skipBytesSafe(in, 4);
    long bytesLeft = Integer.toUnsignedLong(in.readInt());

    long l1, l2, l3, l4;
    int i1, i2, i3, i4, i5, i6, i7, i8, i9;
    short s1;
    byte b1;
    float f1;
    byte[] bArr1;
    long[] lArr1;

    switch (tag) {
      case 0x1:
        // String in UTF-8
        if (isFirstPass) {
          l1 = readId(idSize, in);
          bytesLeft -= idSize;
          bArr1 = new byte[(int) bytesLeft];
          in.readFully(bArr1);
          handler.stringInUTF8(l1, new String(bArr1, StandardCharsets.US_ASCII));
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0x2:
        // Load class
        if (isFirstPass) {
          i1 = in.readInt();
          l1 = readId(idSize, in);
          i2 = in.readInt();
          l2 = readId(idSize, in);
          handler.loadClass(i1, l1, i2, l2);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0x3:
        // Unload class
        if (isFirstPass) {
          i1 = in.readInt();
          handler.unloadClass(i1);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0x4:
        // Stack frame
        if (isFirstPass) {
          l1 = readId(idSize, in);
          l2 = readId(idSize, in);
          l3 = readId(idSize, in);
          l4 = readId(idSize, in);
          i1 = in.readInt();
          i2 = in.readInt();
          handler.stackFrame(l1, l2, l3, l4, i1, i2);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0x5:
        // Stack trace
        if (isFirstPass) {
          i1 = in.readInt();
          i2 = in.readInt();
          i3 = in.readInt();
          bytesLeft -= 12;
          lArr1 = new long[(int) bytesLeft/idSize];
          for (int i=0; i<lArr1.length; i++) {
            lArr1[i] = readId(idSize, in);
          }
          handler.stackTrace(i1, i2, i3, lArr1);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0x6:
        // Alloc sites
        if (isFirstPass) {
          s1 = in.readShort();
          f1 = in.readFloat();
          i1 = in.readInt();
          i2 = in.readInt();
          l1 = in.readLong();
          l2 = in.readLong();
          i3 = in.readInt();    // num of sites that follow

          AllocSite[] allocSites = new AllocSite[i3];
          for (int i=0; i<allocSites.length; i++) {
            b1 = in.readByte();
            i4 = in.readInt();
            i5 = in.readInt();
            i6 = in.readInt();
            i7 = in.readInt();
            i8 = in.readInt();
            i9 = in.readInt();

            allocSites[i] = new AllocSite(b1, i4, i5, i6, i7, i8, i9);
          }
          handler.allocSites(s1, f1, i1, i2, l1, l2, allocSites);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0x7: 
        // Heap summary
        if (!isFirstPass) {
          i1 = in.readInt();
          i2 = in.readInt();
          l1 = in.readLong();
          l2 = in.readLong();
          handler.heapSummary(i1, i2, l1, l2);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0xa:
        // Start thread
        if (isFirstPass) {
          i1 = in.readInt();
          l1 = readId(idSize, in);
          i2 = in.readInt();
          l2 = readId(idSize, in);
          l3 = readId(idSize, in);
          l4 = readId(idSize, in);
          handler.startThread(i1, l1, i2, l2, l3, l4);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0xb:
        // End thread
        if (isFirstPass) {
          i1 = in.readInt();
          handler.endThread(i1);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0xc:
        // Heap dump
        if (isFirstPass) {
          handler.heapDump();
        }
        while (bytesLeft > 0) {
          bytesLeft -= parseHeapDump(in, idSize, isFirstPass);
        }
        if (!isFirstPass) {
          handler.heapDumpEnd();
        }
        break;

      case 0x1c:
        // Heap dump segment
        if (isFirstPass) {
          handler.heapDumpSegment();
        }
        while (bytesLeft > 0) {
          bytesLeft -= parseHeapDump(in, idSize, isFirstPass);
        }
        break;

      case 0x2c:
        // Heap dump end (of segments)
        if (!isFirstPass) {
          handler.heapDumpEnd();
        }
        break;

      case 0xd:
        // CPU samples
        if (isFirstPass) {
          i1 = in.readInt();
          i2 = in.readInt();    // num samples that follow

          CPUSample[] samples = new CPUSample[i2];
          for (int i=0; i<samples.length; i++) {
            i3 = in.readInt();
            i4 = in.readInt();
            samples[i] = new CPUSample(i3, i4);
          }
          handler.cpuSamples(i1, samples);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      case 0xe: 
        // Control settings
        if (isFirstPass) {
          i1 = in.readInt();
          s1 = in.readShort();
          handler.controlSettings(i1, s1);
        } else {
          skipLongBytes(in, bytesLeft);
        }
        break;

      default:
        throw new HprofParserException("Unexpected top-level record type: " + tag);
    }
    
    return false;
  }

  // returns number of bytes parsed
  private int parseHeapDump(DataInput in, int idSize, boolean isFirstPass) throws IOException {

    byte tag = in.readByte();
    int bytesRead = 1;

    long l1, l2, l3, l4, l5, l6, l7;
    int i1, i2;
    short s1, s2, s3;
    byte b1;
    byte[] bArr1;
    long [] lArr1;

    switch (tag) {

      case -1:    // 0xFF
        // Root unknown
        l1 = readId(idSize, in);
        if (isFirstPass) {
          handler.rootUnknown(l1);
        }
        bytesRead += idSize;
        break;

      case 0x01:
        // Root JNI global
        l1 = readId(idSize, in);
        l2 = readId(idSize, in);
        if (isFirstPass) {
          handler.rootJNIGlobal(l1, l2);
        }
        bytesRead += 2 * idSize;
        break;

      case 0x02:
        // Root JNI local
        l1 = readId(idSize, in);
        i1 = in.readInt();
        i2 = in.readInt();
        if (isFirstPass) {
          handler.rootJNILocal(l1, i1, i2);
        }
        bytesRead += idSize + 8;
        break;

      case 0x03:
        // Root Java frame
        l1 = readId(idSize, in);
        i1 = in.readInt();
        i2 = in.readInt();
        if (isFirstPass) {
          handler.rootJavaFrame(l1, i1, i2);
        }
        bytesRead += idSize + 8;
        break;

      case 0x04:
        // Root native stack
        l1 = readId(idSize, in);
        i1 = in.readInt();
        if (isFirstPass) {
          handler.rootNativeStack(l1, i1);
        }
        bytesRead += idSize + 4;
        break;

      case 0x05:
        // Root sticky class
        l1 = readId(idSize, in);
        if (isFirstPass) {
          handler.rootStickyClass(l1);
        }
        bytesRead += idSize;
        break;

      case 0x06:
        // Root thread block
        l1 = readId(idSize, in);
        i1 = in.readInt();
        if (isFirstPass) {
          handler.rootThreadBlock(l1, i1);
        }
        bytesRead += idSize + 4;
        break;
        
      case 0x07:
        // Root monitor used
        l1 = readId(idSize, in);
        if (isFirstPass) {
          handler.rootMonitorUsed(l1);
        }
        bytesRead += idSize;
        break;

      case 0x08:
        // Root thread object
        l1 = readId(idSize, in);
        i1 = in.readInt();
        i2 = in.readInt();
        if (isFirstPass) {
          handler.rootThreadObj(l1, i1, i2);
        }
        bytesRead += idSize + 8;
        break;

      case 0x20:
        // Class dump
        l1 = readId(idSize, in);
        i1 = in.readInt();
        l2 = readId(idSize, in);
        l3 = readId(idSize, in);
        l4 = readId(idSize, in);
        l5 = readId(idSize, in);
        l6 = readId(idSize, in);
        l7 = readId(idSize, in);
        i2 = in.readInt();
        bytesRead += idSize * 7 + 8;
        
        /* Constants */
        s1 = in.readShort();    // number of constants
        bytesRead += 2;
        Preconditions.checkState(s1 >= 0);
        Constant[] constants = new Constant[s1];
        for (int i=0; i<s1; i++) {
          short constantPoolIndex = in.readShort();
          byte btype = in.readByte();
          bytesRead += 3;
          Type type = Type.hprofTypeToEnum(btype);
          Value<?> v = null;

          switch (type) {
            case OBJ:
              long vid = readId(idSize, in);
              bytesRead += idSize;
              v = new Value<>(type, vid);
              break;
            case BOOL:
              boolean vbool = in.readBoolean();
              bytesRead += 1;
              v = new Value<>(type, vbool);
              break;
            case CHAR:
              char vc = in.readChar();
              bytesRead += 2;
              v = new Value<>(type, vc);
              break;
            case FLOAT:
              float vf = in.readFloat();
              bytesRead += 4;
              v = new Value<>(type, vf);
              break;
            case DOUBLE:
              double vd = in.readDouble();
              bytesRead += 8;
              v = new Value<>(type, vd);
              break;
            case BYTE:
              byte vbyte = in.readByte();
              bytesRead += 1;
              v = new Value<>(type, vbyte);
              break;
            case SHORT:
              short vs = in.readShort();
              bytesRead += 2;
              v = new Value<>(type, vs);
              break;
            case INT:
              int vi = in.readInt();
              bytesRead += 4;
              v = new Value<>(type, vi);
              break;
            case LONG:
              long vl = in.readLong();
              bytesRead += 8;
              v = new Value<>(type, vl);
              break;
          }

          constants[i] = new Constant(constantPoolIndex, v);
        }

        /* Statics */
        s2 = in.readShort();    // number of static fields
        bytesRead += 2;
        Preconditions.checkState(s2 >= 0);
        Static[] statics = new Static[s2];
        for (int i=0; i<s2; i++) {
          long staticFieldNameStringId = readId(idSize, in);
          byte btype = in.readByte();
          bytesRead += idSize + 1;
          Type type = Type.hprofTypeToEnum(btype);
          Value<?> v = null;

          switch (type) {
            case OBJ:     // object
              long vid = readId(idSize, in);
              bytesRead += idSize;
              v = new Value<>(type, vid);
              break;
            case BOOL:     // boolean
              boolean vbool = in.readBoolean();
              bytesRead += 1;
              v = new Value<>(type, vbool);
              break;
            case CHAR:     // char
              char vc = in.readChar();
              bytesRead += 2;
              v = new Value<>(type, vc);
              break;
            case FLOAT:     // float
              float vf = in.readFloat();
              bytesRead += 4;
              v = new Value<>(type, vf);
              break;
            case DOUBLE:     // double
              double vd = in.readDouble();
              bytesRead += 8;
              v = new Value<>(type, vd);
              break;
            case BYTE:     // byte
              byte vbyte = in.readByte();
              bytesRead += 1;
              v = new Value<>(type, vbyte);
              break;
            case SHORT:     // short
              short vs = in.readShort();
              bytesRead += 2;
              v = new Value<>(type, vs);
              break;
            case INT:    // int
              int vi = in.readInt();
              bytesRead += 4;
              v = new Value<>(type, vi);
              break;
            case LONG:    // long
              long vl = in.readLong();
              bytesRead += 8;
              v = new Value<>(type, vl);
              break;
          }

          statics[i] = new Static(staticFieldNameStringId, v);
        }

        /* Instance fields */
        s3 = in.readShort();    // number of instance fields
        bytesRead += 2;
        Preconditions.checkState(s3 >= 0);
        InstanceField[] instanceFields = new InstanceField[s3];
        for (int i=0; i<s3; i++) {
          long fieldNameStringId = readId(idSize, in);
          byte btype = in.readByte();
          bytesRead += idSize + 1;
          Type type = Type.hprofTypeToEnum(btype);
          instanceFields[i] = new InstanceField(fieldNameStringId, type);
        }

        /**
         * We need to know the types of the values in an instance record when
         * we parse that record.  To do that we need to look up the class and 
         * its superclasses.  So we need to store class records in a hash 
         * table.
         */
        if (isFirstPass) {
          classMap.put(l1, new ClassInfo(l1, l2, i2, instanceFields));
        }
        if (isFirstPass) {
          handler.classDump(l1, i1, l2, l3, l4, l5, l6, l7, i2, constants,
            statics, instanceFields);
        }
        break;

      case 0x21: {
        // Instance dump
        /**
         * because class dump records come *after* instance dump records,
         * we don't know how to interpret the values yet.  we have to
         * record the instances and process them at the end.
         */
        int arrayLength;
        if (!isFirstPass) {
          l1 = readId(idSize, in);
          i1 = in.readInt();
          l2 = readId(idSize, in);    // class obj id
          arrayLength = in.readInt();    // num of bytes that follow
          Preconditions.checkState(arrayLength >= 0);

          bArr1 = new byte[arrayLength];
          in.readFully(bArr1);
          processInstance(new Instance(l1, i1, l2, bArr1), idSize);
        } else {
          skipBytesSafe(in, idSize + 4 + idSize);
          arrayLength = in.readInt();    // num of bytes that follow
          skipBytesSafe(in, arrayLength);
        }

        bytesRead += idSize * 2 + 8 + arrayLength;
        break;
      }

      case 0x22: {
        // Object array dump
        int arrayLength;
        if (isFirstPass) {
          l1 = readId(idSize, in);
          i1 = in.readInt();
          arrayLength = in.readInt();    // number of elements
          l2 = readId(idSize, in);

          Preconditions.checkState(arrayLength >= 0);
          lArr1 = new long[arrayLength];
          for (int i=0; i<arrayLength; i++) {
            lArr1[i] = readId(idSize, in);
          }
          handler.objArrayDump(l1, i1, l2, lArr1);
        } else {
          skipBytesSafe(in, idSize + 4);
          arrayLength = in.readInt();    // number of elements
          skipBytesSafe(in, arrayLength * idSize + idSize);
        }
        bytesRead += (2 + arrayLength) * idSize + 8;
        break;
      }

      case 0x23:
        // Primitive array dump
        l1 = readId(idSize, in);
        i1 = in.readInt();
        i2 = in.readInt();    // number of elements
        b1 = in.readByte();
        bytesRead += idSize + 9;

        Preconditions.checkState(i2 >= 0);
        Type t = Type.hprofTypeToEnum(b1);
        int arraySizeBytes = i2 * (t == Type.OBJ ? idSize : t.sizeInBytes());
        bytesRead += arraySizeBytes;

        if (isFirstPass) {
          switch (t) {
            case OBJ: {
              long[] vs = new long[i2];
              for (int i=0; i<vs.length; i++) {
                vs[i] = readId(idSize, in);
              }
              handler.primObjectArrayDump(l1, i1, vs);
              break;
            }
            case BOOL: {
              boolean[] vs = new boolean[i2];
              for (int i=0; i<vs.length; i++) {
                vs[i] = in.readBoolean();
              }
              handler.primArrayDump(l1, i1, vs);
              break;
            }
            case CHAR:{
              char[] vs = new char[i2];
              for (int i=0; i<vs.length; i++) {
                vs[i] = in.readChar();
              }
              handler.primArrayDump(l1, i1, vs);
              break;
            }
            case FLOAT:{
              float[] vs = new float[i2];
              for (int i=0; i<vs.length; i++) {
                vs[i] = in.readFloat();
              }
              handler.primArrayDump(l1, i1, vs);
              break;
            }
            case DOUBLE: {
              double[] vs = new double[i2];
              for (int i=0; i<vs.length; i++) {
                vs[i] = in.readDouble();
              }
              handler.primArrayDump(l1, i1, vs);
              break;
            }
            case BYTE: {
              byte[] vs = new byte[i2];
              in.readFully(vs);
              handler.primArrayDump(l1, i1, vs);
              break;
            }
            case SHORT: {
              short[] vs = new short[i2];
              for (int i=0; i<vs.length; i++) {
                vs[i] = in.readShort();
              }
              handler.primArrayDump(l1, i1, vs);
              break;
            }
            case INT: {
              int[] vs = new int[i2];
              for (int i=0; i<vs.length; i++) {
                vs[i] = in.readInt();
              }
              handler.primArrayDump(l1, i1, vs);
              break;
            }
            case LONG: {
              long[] vs = new long[i2];
              for (int i=0; i<vs.length; i++) {
                vs[i] = in.readLong();
              }
              handler.primArrayDump(l1, i1, vs);
              break;
            }
          }
        } else {
          skipBytesSafe(in, arraySizeBytes);
        }
        break;

      default:
        throw new HprofParserException("Unexpected heap dump sub-record type: " + tag);
    }

    return bytesRead;
    
  }

  private void processInstance(Instance i, int idSize) throws IOException {
    ByteArrayInputStream bs = new ByteArrayInputStream(i.packedValues);
    DataInputStream input = new DataInputStream(bs);

    ArrayList<Value<?>> values = new ArrayList<>();

    // superclass of Object is 0
    long nextClass = i.classObjId;
    while (nextClass != 0) {
      ClassInfo ci = classMap.get(nextClass);
      nextClass = ci.superClassObjId;
      for (InstanceField field : ci.instanceFields) {
        Value<?> v = null;
        switch (field.type) {
          case OBJ:     // object
            long vid = readId(idSize, input);
            v = new Value<>(field.type, vid);
            break;
          case BOOL:     // boolean
            boolean vbool = input.readBoolean();
            v = new Value<>(field.type, vbool);
            break;
          case CHAR:     // char
            char vc = input.readChar();
            v = new Value<>(field.type, vc);
            break;
          case FLOAT:     // float
            float vf = input.readFloat();
            v = new Value<>(field.type, vf);
            break;
          case DOUBLE:     // double
            double vd = input.readDouble();
            v = new Value<>(field.type, vd);
            break;
          case BYTE:     // byte
            byte vbyte = input.readByte();
            v = new Value<>(field.type, vbyte);
            break;
          case SHORT:     // short
            short vs = input.readShort();
            v = new Value<>(field.type, vs);
            break;
          case INT:    // int
            int vi = input.readInt();
            v = new Value<>(field.type, vi);
            break;
          case LONG:    // long
            long vl = input.readLong();
            v = new Value<>(field.type, vl);
            break;
        }
        values.add(v);
      }
    }
    Value<?>[] valuesArr = new Value[values.size()];
    valuesArr = values.toArray(valuesArr);
    handler.instanceDump(i.objId, i.stackTraceSerialNum, i.classObjId, valuesArr);
  }

  private static long readId(int idSize, DataInput in) throws IOException {
    long id = -1;
    if (idSize == 4) {
      id = in.readInt();
      id &= 0x00000000ffffffff;     // undo sign extension
    } else if (idSize == 8) {
      id = in.readLong();
    } else {
      throw new IllegalArgumentException("Invalid identifier size " + idSize);
    }

    return id;
  }

  private static void skipLongBytes(DataInput in, long n) throws IOException {
    if (n < 0) {
      throw new IllegalArgumentException("n < 0: " + n);
    }

    if (n <= Integer.MAX_VALUE) {
      skipBytesSafe(in, (int) n);
    } else {
      long left = n;
      while (left > 0) {
        if (left > Integer.MAX_VALUE) {
          skipBytesSafe(in, Integer.MAX_VALUE);
        } else {
          skipLongBytes(in, (int) left);
        }
        left -= Integer.MAX_VALUE;
      }
    }
  }

  private static void skipBytesSafe(DataInput in, int n) throws IOException {
    int skipped = in.skipBytes(n);
    if (n != skipped) {
      throw new IllegalStateException("Expected to skip " + n + " bytes, but only " + skipped + " were skipped");
    }
  }
}
