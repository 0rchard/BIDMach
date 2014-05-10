package BIDMach.models

import BIDMat.{Mat,SBMat,CMat,DMat,FMat,IMat,HMat,GMat,GIMat,GSMat,SMat,SDMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMach.datasources._
import BIDMach.updaters._
import BIDMach.Learner
import BIDMat.Sorting._
import scala.util.control.Breaks
import jcuda._
import jcuda.runtime._
import jcuda.runtime.JCuda._
import jcuda.runtime.cudaMemcpyKind._
import edu.berkeley.bid.CUMAT

object RandForest {
  import jcuda.runtime._
  import jcuda.runtime.JCuda._
  import jcuda.runtime.cudaError._
  import jcuda.runtime.cudaMemcpyKind._
  import scala.util.hashing.MurmurHash3
  import edu.berkeley.bid.CUMACH

  val NegativeInfinityF = 0xff800000.toFloat
  val NegativeInfinityI = 0xff800000.toInt
  val ITree = 0; val INode = 1; val JFeat = 2; val IFeat = 3; val IVFeat = 4; val ICat = 5
  
  def rhash(v1:Int, v2:Int, v3:Int, nb:Int):Int = {
    math.abs(MurmurHash3.mix(MurmurHash3.mix(v1, v2), v3) % nb)
  }
  
  def packFields(itree:Int, inode:Int, jfeat:Int, ifeat:Int, ivfeat:Int, icat:Int, fieldlengths:IMat):Long = {
    icat.toLong + 
    ((ivfeat.toLong + 
        ((ifeat.toLong + 
            ((jfeat.toLong + 
                ((inode.toLong + 
                    (itree.toLong << fieldlengths(INode))
                    ) << fieldlengths(JFeat))
                ) << fieldlengths(IFeat))
          ) << fieldlengths(IVFeat))
      ) << fieldlengths(ICat))
  }
  
  def extractAbove(fieldNum : Int, packedFields : Long, FieldShifts : IMat) : Int = {
    (packedFields >>> FieldShifts(fieldNum)).toInt
  }

  def extractField(fieldNum : Int, packedFields : Long, FieldShifts : IMat, FieldMasks : IMat) : Int = {
    (packedFields >>> FieldShifts(fieldNum)).toInt & FieldMasks(fieldNum) 
  }

  def getFieldShifts(fL : IMat) : IMat = {
    val out = izeros(1, fL.length)
    var i = fL.length - 2
    while (i >= 0) {
      out(i) = out(i+1) + fL(i+1)
      i -= 1
    }
    out
  }

  def getFieldMasks(fL : IMat) : IMat = {
    val out = izeros(1, fL.length)
    var i = 0
    while (i < fL.length) {
      out(i) = (1 << fL(i)) - 1
      i += 1
    }
    out
  }

  def sortLongs(a:Array[Long], useGPU : Boolean) {
    // println("sortLongs: Length: " + a.length + " Bytes: " + Sizeof.LONG*a.length);
    if (useGPU) {
      val (p, b, t) = GPUmem
      // println("Current GPUmem left Before: " + p + " Potential After: " + 1f*(b - Sizeof.LONG*a.length)/t)
      // tic
      val memorySize = Sizeof.LONG*a.length
      val deviceData : Pointer = new Pointer();
      cudaMalloc(deviceData, memorySize);
      cudaMemcpy(deviceData, Pointer.to(a), memorySize, cudaMemcpyKind.cudaMemcpyHostToDevice);
      // val t1 = toc
      // println("Send to GPU time: " + t1)
      // tic
      val err = CUMAT.lsort(deviceData, a.length, 1)
      if (err != 0) {throw new RuntimeException("lsort: CUDA kernel error in lsort " + cudaGetErrorString(err))}
      // val t2 = toc
      // println("GPU: sort time: " + t2)
      // tic
      cudaMemcpy(Pointer.to(a), deviceData, memorySize, cudaMemcpyKind.cudaMemcpyDeviceToHost);
      cudaFree(deviceData);
      // val t3 = toc
      // println("Send back to GPU time plus dealloc: " + t3)
    } else {

      def comp(i1 : Int, i2 : Int) : Int = {
        val a1 = a(i1)
        val a2 = a(i2)
        return compareLong(a1, a2)
      }

      def swap(i1 : Int, i2 : Int) = {
        val tempA = a(i2)
        a(i2) = a(i1)
        a(i1) = tempA
      }
      // tic
      quickSort(comp, swap, 0, a.length)
      // val t1 = toc
      // println("CPU: sort time: " + t1)
    }

  }

  def compareLong(i : Long, j : Long) : Int = {
    if (i < j) {
      return -1
    } else if (i == j) {
      return 0
    } else {
      return 1
    }
  }

  // def treePackkk(sfdata : Mat, treenodes : Mat, cats : Mat, nsamps : Int, fieldLengths: Mat) : Mat = {
  //   (sfdata, treenodes, cats, nsamps, fieldLengths) match {
  //     case (sfd : GIMat, tn : GIMat, cts : GSMat, ns : Int, fL :GIMat) => {
  //       val out = sfd.izeros(tn.nrows * ns * cts.nnz * 2, 1)
  //       // public sta// tic native int treePack(Pointer id, Pointer tn, Pointer icats, Pointer jc, Pointer out, Pointer fl, int nrows, int ncols, int ntrees, int nsamps);
  //       val ntrees = tn.nrows
  //       CUMACH.treePack(sfd.data, tn.data, cts.ir, cts.jc, out.data, fL.data, sfd.nrows, sfd.ncols, ntrees, ns)
  //       out
  //     }
  //     case (sfd : IMat, tn : IMat, cts : SMat, ns : Int, fL : GIMat) => {
  //       val out = sfd.izeros(tn.nrows * ns * cts.nnz * 2, 1)
  //       val c = new IMat(cts.ir.length, 1, cts.ir) - 1
  //       val cjc = new IMat(cts.jc.length, 1, cts.jc) - 1
  //       treePack(sfd, tn, c, out, cjc, ns, fL)
  //       out
  //     }
  //   }
  // }

  def treePackk(sfdata : Mat, treenodes : Mat, cats : Mat, nsamps : Int, fieldlengths: Mat, useGPU : Boolean) : Array[Long] = {
    // println("treePack")
    (sfdata, treenodes, cats, nsamps, fieldlengths, useGPU) match {
      case (sfd : IMat, tn : IMat, cts : SMat, ns : Int, fL : IMat, true) => {
        // treePack(Pointer id, Pointer tn, Pointer icats, Pointer jc, Pointer out, Pointer fl, int nrows, int ncols, int ntrees, int nsamps);
        // tic
        val ntrees = tn.nrows
        val out = new Array[Long](ntrees * nsamps * cts.nnz)
        // println("treePack: Length: " + out.length + " Bytes: " + Sizeof.LONG*out.length);
        val (p, b, t) = GPUmem
        // println("treePack: Current GPUmem left Before: " + p + " Potential After: " + 1f*(b - Sizeof.LONG*out.length)/t)
        val memorySize = Sizeof.LONG*out.length
        val deviceData : Pointer = new Pointer();
        cudaMalloc(deviceData, memorySize);
        // cudaMemcpy(deviceData, Pointer.to(out), memorySize, cudaMemcpyKind.cudaMemcpyHostToDevice);
        val gFd = GIMat(sfd) 
        val gsCats = GSMat(cts)
        val gsCatsJC = (GIMat(new IMat(cts.jc.length, 1, cts.jc) - 1).data);
        val gsCatsIR = (GIMat(new IMat(cts.ir.length, 1, cts.ir) - 1).data);
        val giTreenodes = GIMat(tn)
        val gifL = GIMat(fL)
        // val t1 = toc
        // println("Treepack: GPU Allocation time: " + t1)
        // tic
        val err = CUMACH.treePack(gFd.data, giTreenodes.data, gsCatsIR, gsCatsJC, deviceData, gifL.data, gFd.nrows, gFd.ncols, ntrees, ns)
        if (err != 0) {throw new RuntimeException("treePack: CUDA kernel error in CUMACH.treePack " + cudaGetErrorString(err))}
        // val t2 = toc
        // println("Treepack: GPU Run time: " + t2)
        // tic
        cudaMemcpy(Pointer.to(out), deviceData, memorySize, cudaMemcpyKind.cudaMemcpyDeviceToHost);
        cudaFree(deviceData);
        gFd.free; gsCats.free; giTreenodes.free; gifL.free
        // val t3 = toc
        // println("Treepack: GPU dealloc and transfer back time: " + t3)
        out
      }
      case (sfd : IMat, tn : IMat, cts : SMat, ns : Int, fL : IMat, false) => {
        val out = new Array[Long](tn.nrows * nsamps * cts.nnz)
        val c = new IMat(cts.ir.length, 1, cts.ir) - 1
        val cjc = new IMat(cts.jc.length, 1, cts.jc) - 1
        // tic
        treePack(sfd, tn, c, out, cjc, ns, fL)
        // val t1 = toc
        // println("TreePack: CPU Run time: " + t1)
        out
      }
    }
  }


  def treePack(fdata:IMat, treenodes:IMat, cats:IMat, out:Array[Long], jc:IMat, nsamps:Int, fieldlengths:IMat) = {
    val nfeats = fdata.nrows
    val nitems = fdata.ncols
    val ntrees = treenodes.nrows
    val ncats = cats.nrows
    val nnzcats = cats.length
    var icol = 0
    while (icol < nitems) {
      var jci = jc(icol)
      val jcn = jc(icol+1)
      var itree = 0
      while (itree < ntrees) {
        val inode = treenodes(itree, icol)
        var jfeat = 0
        while (jfeat < nsamps) {
          val ifeat = rhash(itree, inode, jfeat, nfeats)
          val ivfeat = fdata(ifeat, icol)
          var jc = jci
          while (jc < jcn) {
            // // println("itree: " + itree + " inode: " + inode + " jfeat: " + jfeat + " ifeat: " + ifeat + " ivfeat: " + ivfeat + " cats(jc): " + cats(jc))
            out(jfeat + nsamps * (itree + ntrees * jc)) = packFields(itree, inode, jfeat, ifeat, ivfeat, cats(jc), fieldlengths)
            jc += 1
          }
          jfeat += 1
        }
        itree += 1
      }
      icol += 1
    }
    out
  }
  

  // TODO: move 
  def treePackAndSort(sfd : IMat, tn : IMat, cts : SMat, nsps : Int, fL : IMat, useGPU : Boolean) : (Array[Long], Array[Float]) = {
    val b = getNumBlocksForTreePack(sfd, tn, cts, nsps)
    // println("b: " + b)
    val n = (sfd.ncols*1f/b).toInt 
    // println("ncols: " + sfd.ncols)
    // println("n: " + n)
    var bb = 1
    var fInds = new Array[Long](0);
    var fCounts = new Array[Float](0);
    while (bb <= b) {
      var r = 0->0
      if ( (sfd.ncols - (bb - 1) * n) >= n) {
        r = ( ((bb - 1) * n) -> bb*n )
        // println("0: r: " + r)
      } else if ((sfd.ncols - (bb - 1) * n ) > 0) {
        r = ( ((bb - 1) * n) -> sfd.nrows)
       // println("1: r: " + r)
      }
      if (r.length > 0) {
        // println("treePack and Sort")
        val treePacked : Array[Long] = RandForest.treePackk(sfd(?,r), tn(?,r) , cts(?, r), nsps, fL, true)
        RandForest.sortLongs(treePacked, true)
        val c = RandForest.countC(treePacked)
        val inds = new Array[Long](c)
        val indsCounts = new Array[Float](c)
        RandForest.makeC(treePacked, inds, indsCounts)
        // def mergeC(ind1:Array[Long], counts1:Array[Float], ind2:Array[Long], counts2:Array[Float]):Int
        // def mergeV(ind1:Array[Long], counts1:Array[Float], ind2:Array[Long], counts2:Array[Float], ind3:Array[Long], counts3:Array[Float]):Int = {
        if (bb>1) {
          val mC = RandForest.mergeC(fInds, fCounts, inds, indsCounts)
          val mInds = new Array[Long](mC)
          val mCounts = new Array[Float](mC)
          RandForest.mergeV(fInds, fCounts, inds, indsCounts, mInds, mCounts)
          fInds = mInds
          fCounts = mCounts
        } else {
          fInds = inds
          fCounts = indsCounts
        }
      }
      bb+=1
    }
    (fInds, fCounts)
  } 

  /**
   * Given what is available in the GPU determines a block size of data that fits into GPU memory
   */
  def getNumBlocksForTreePack(sfd : IMat, tn : IMat, cts : SMat, nsps : Int) : Int = {
    val ntrees = tn.nrows
    val tBytes = Sizeof.LONG * ntrees * (nsps/1e9f) * (cts.nnz)+ Sizeof.INT * sfd.length/1e9f + Sizeof.INT * tn.length/1e9f + Sizeof.INT/1e9f * cts.nnz 
    // println("tBytes: " + tBytes)
    val (p, b, t) = GPUmem
    // println("availableBytes/1e9f: " + b/1e9f)
    val aBytes = b/1e9f - 0.5f * t/1e9f
    // println("aBytes/1e9f: " + aBytes/1e9f)
    if (aBytes > 0f) {
      // println("fract: " + ((tBytes * 1f)/aBytes))
      math.ceil(((tBytes * 1f)/aBytes)).toInt
    } else {
      throw new RuntimeException("getBlockSizeForTreePack: not enough GPUmem available" )
      0
    }
  }

  // Find boundaries where (key >> shift) changes
  def findBoundariess(keys:Array[Long], jc:IMat, shift:Int, useGPU : Boolean) {
    if (useGPU) {
      // public static native int findBoundaries(Pointer keys, Pointer jc, int n, int njc, int shift);
      val dkeys : Pointer = new Pointer()
      val memorySize = Sizeof.LONG*keys.length
      cudaMalloc(dkeys, memorySize);
      cudaMemcpy(dkeys, Pointer.to(keys), memorySize, cudaMemcpyKind.cudaMemcpyHostToDevice);
      val gjc = GIMat(jc)
      val err = CUMACH.findBoundaries(dkeys, gjc.data, keys.length, jc.length, shift)
      if (err != 0) {throw new RuntimeException("findBoundaries: CUDA kernel error in CUMACH.findBoundaries " + cudaGetErrorString(err))}
      cudaFree(dkeys)
      jc <-- gjc
      gjc.free
    } else {
      findBoundaries(keys, jc, shift)
    }
  }
  
  def findBoundaries(keys:Array[Long], jc:IMat, shift:Int) { 
    var oldv = -1
    var v = -1
    var i = 0
    while (i < keys.length) {
      v = (keys(i) >>> shift).toInt
      while (oldv < v) {
        oldv += 1
        jc(oldv) = i
      }
      i += 1
    }
    while (oldv < jc.length - 1) {
      oldv += 1
      jc(oldv) = i
    }
  }

  // def minImpurity(keys:Array[Long], cnts:IMat, outv:IMat, outf:IMat, outg:FMat, outc:IMat, jc:IMat, fieldlens:IMat, 
     //           ncats:Int, fnum:Int)
  def minImpurityy(keys:Array[Long], cnts:IMat, outv:IMat, outf:IMat, outg:FMat, outc:IMat, jc:IMat, fieldlens:IMat, 
               ncats:Int, fnum:Int, useGPU : Boolean) {
    if (useGPU) {
      // public static native int minImpurity(Pointer keys, Pointer counts, Pointer outv, Pointer outf, Pointer outg, Pointer outc, Pointer jc, Pointer fieldlens, int nnodes, int ncats, int nsamps, int impType);
      val dcountsM = GIMat(cnts)
      val doutvM = GIMat(outv)
      val doutfM = GIMat(outf)
      val doutgM = GMat(outg)
      val doutcM = GIMat(outc)
      val djcM = GIMat(jc)
      val dfieldlensM = GIMat(fieldlens)
      val dkeys : Pointer = new Pointer()
      val memorySize = Sizeof.LONG*keys.length
      cudaMalloc(dkeys, memorySize)
      cudaMemcpy(dkeys, Pointer.to(keys), memorySize, cudaMemcpyKind.cudaMemcpyHostToDevice);
      val err = CUMACH.minImpurity(dkeys, dcountsM.data, doutvM.data, doutfM.data, doutgM.data, doutcM.data, djcM.data, dfieldlensM.data, outv.nrows, ncats, outv.ncols, fnum)
      if (err != 0) {throw new RuntimeException("minImpurity: CUDA kernel error in CUMACH.minImpurity " + cudaGetErrorString(err))}
      outv <-- doutvM; 
      outf <-- doutfM; 
      outg <-- doutgM; 
      outc<-- doutcM;
      dcountsM.free; doutvM.free; doutfM.free; doutgM.free; doutcM.free; dfieldlensM.free; djcM.free
      cudaFree(dkeys)
    } else {
      minImpurity(keys, cnts, outv, outf, outg, outc, jc, fieldlens, ncats, fnum)
    }
  }
  
  trait imptyType {
    val update: (Int)=>Float;
    val result: (Float, Int)=>Float;
  }
  
  object entImpurity extends imptyType {
    def updatefn(a:Int):Float = { val v = math.max(a,1).toFloat; v * math.log(v).toFloat }
    def resultfn(acc:Float, tot:Int):Float = { val v = math.max(tot,1).toFloat; math.log(v).toFloat - acc / v }
    val update = updatefn _ ;
    val result = resultfn _ ;
  }
  
  object giniImpurity extends imptyType {
    def updatefn(a:Int):Float = { val v = a.toFloat; v * v }
    def resultfn(acc:Float, tot:Int) = { val v = math.max(tot,1).toFloat; 1f - acc / (v * v) }
    val update = updatefn _ ;
    val result = resultfn _ ;
  }
  
  val imptyFunArray = Array[imptyType](entImpurity,giniImpurity)
  
  // Pass in one of the two object above as the last argument (imptyFns) to control the impurity
  // outv should be an nsamps * nnodes array to hold the feature threshold value
  // outf should be an nsamps * nnodes array to hold the feature index
  // outg should be an nsamps * nnodes array holding the impurity gain (use maxi2 to get the best)
  // jc should be a zero-based array that points to the start and end of each group of fixed node,jfeat

  def minImpurity(keys:Array[Long], cnts:IMat, outv:IMat, outf:IMat, outg:FMat, outc:IMat, jc:IMat, fieldlens:IMat, 
      ncats:Int, fnum:Int) = {
    
    val imptyFns = imptyFunArray(fnum)

    val totcounts = izeros(1,ncats);
    val counts = izeros(1,ncats);
    val fieldshifts = getFieldShifts(fieldlens);
    val fieldmasks = getFieldMasks(fieldlens);


    var j = 0;
    var tot = 0;
    var tott = 0;
    var acc = 0f;
    var acct = 0f;
    var i = 0;
    while (i < jc.length - 1) {
      val jci = jc(i);
      val jcn = jc(i+1);

      totcounts.clear;
      counts.clear;
      tott = 0;
      j = jci;
      var maxcnt = -1;
      var imaxcnt = -1;
      while (j < jcn) {                     // First get the total counts for each
        val key = keys(j)
        val cnt = cnts(j)
        val icat = extractField(ICat, key, fieldshifts, fieldmasks);
        val newcnt = totcounts(icat) + cnt;
        totcounts(icat) = newcnt;
        tott += cnt;
        if (newcnt > maxcnt) {
          maxcnt = newcnt;
          imaxcnt = icat;
        }
        j += 1;
      }
      acct = 0; 
      j = 0;
      while (j < ncats) {                  // Get the impurity for the node
        acct += imptyFns.update(totcounts(j));
        j += 1
      }
//      if (i < 32)  // println("scala %d %d %f" format (i, tott, acct))
      val nodeImpty = imptyFns.result(acct, tott);
      
      var lastival = -1
      var minImpty = 1e7f // Float.MaxValue
      var lastImpty = 1e7f // Float.MaxValue
      var partv = -1
      var besti = -1
      acc = 0;
      tot = 0;
      j = jci;
      while (j < jcn) {                   // Then incrementally update top and bottom impurities and find min total 
        val key = keys(j)
        // val ITree = 0; val INode = 1; val JFeat = 2; val IFeat = 3; val IVFeat = 4; val ICat = 5
        // // println("ITree: " + extractField(ITree, key, fieldshifts, fieldmasks) + " INode: " + extractField(INode, key, fieldshifts, fieldmasks) +
        //   " JFeat: " + extractField(JFeat, key, fieldshifts, fieldmasks) + " IFeat: " + extractField(IFeat, key, fieldshifts, fieldmasks) + 
        //   " IVFeat: " + extractField(IVFeat, key, fieldshifts, fieldmasks) + " ICat: " + extractField(ICat, key, fieldshifts, fieldmasks))
        val cnt = cnts(j)
        val ival = extractField(IVFeat, key, fieldshifts, fieldmasks);
        val icat = extractField(ICat, key, fieldshifts, fieldmasks);
        val oldcnt = counts(icat);
        val newcnt = oldcnt + cnt;
        counts(icat) = newcnt;
        val oldcntt = totcounts(icat) - oldcnt;
        val newcntt = totcounts(icat) - newcnt;
        tot += cnt;
        acc += imptyFns.update(newcnt) - imptyFns.update(oldcnt);
        acct += imptyFns.update(newcntt) - imptyFns.update(oldcntt);
        val impty = (tot *1f/tott)*imptyFns.result(acc, tot) + ((tott - tot) *1f/tott)*imptyFns.result(acct, tott - tot)
//        if (i==0) // println("scala pos %d impty %f icat %d cnts %d %d cacc %f %d" format (j, impty,  icat, oldcnt, newcnt, acc, tot))
        if (ival != lastival) {
          if (lastImpty < minImpty) { 
            minImpty = lastImpty;
            partv = ival;
            besti = extractField(IFeat, key, fieldshifts, fieldmasks);
          }
        }
        lastival = ival;
        lastImpty = impty;
        j += 1;
      }
      outv(i) = partv;
      outg(i) = nodeImpty - minImpty;
      outf(i) = besti
      outc(i) = imaxcnt
      i += 1;
    }
    // // println("outv")
    // // println(outv)
    // // println("outg")
    // // println(outg)
    // // println("outf")
    // // println(outf)
    // // println("outc")
    // // println(outc)
  }

  def updateTreeData(packedVals : Array[Long], fL : IMat, ncats : Int, tMI : IMat, d : Int, isLastIteration : Boolean,
      FieldMaskRShifts : IMat, FieldMasks : IMat) = {
    val n = packedVals.length
    val nnodes = (math.pow(2, d).toInt)
    var bgain = NegativeInfinityF; var bthreshold = NegativeInfinityI; var brfeat = -1;
    val catCounts = fL.zeros(1, ncats);
    val catCountsSoFar = fL.zeros(1, ncats);

    var i = 0
    while (i < n) {
        val itree = extractField(ITree, packedVals(i), FieldMaskRShifts, FieldMasks)
        val inode = extractField(INode, packedVals(i), FieldMaskRShifts, FieldMasks)
        val ivfeat = extractField(IVFeat, packedVals(i), FieldMaskRShifts, FieldMasks)
        val uirfeat = extractAbove(IFeat, packedVals(i), FieldMaskRShifts) // u for unique
        val uivfeat = extractAbove(IVFeat, packedVals(i), FieldMaskRShifts)
        val uinode = extractAbove(INode, packedVals(i), FieldMaskRShifts)

        var j = i
        catCounts.clear
        val mybreaks = new Breaks
        import mybreaks.{break, breakable}
        breakable {
          while (j < n) {
            val jcat = extractField(ICat, packedVals(j), FieldMaskRShifts, FieldMasks)
            val ujrfeat = extractAbove(IFeat, packedVals(j), FieldMaskRShifts)
            if (ujrfeat != uirfeat) {
              break()
            }
            catCounts(jcat) += 1f
            j+=1
          }
        }
        val (bCatCount, bCat) = maxi2(catCounts, 2)
        val inext = j // beginning of next feat
        j = i
        catCountsSoFar.clear
        var ujvlastFeat = uivfeat
        while (j < inext && (inext - i)> 10) {
          val ujvfeat = extractAbove(IVFeat, packedVals(j), FieldMaskRShifts)
          val jvfeat = extractField(IVFeat, packedVals(j), FieldMaskRShifts, FieldMasks)
          val jrfeat = extractField(IFeat, packedVals(j), FieldMaskRShifts, FieldMasks)
          val jcat = extractField(ICat, packedVals(j), FieldMaskRShifts, FieldMasks)
          if (ujvlastFeat != ujvfeat || (j == (inext - 1))) {
            val gain = getGain(catCountsSoFar, catCounts)
            if (gain > bgain && gain > 0f) {
              bgain = gain
              bthreshold = jvfeat
              brfeat = jrfeat
            }
            ujvlastFeat = ujvfeat
          }
          catCountsSoFar(jcat) += 1f

          j+=1
        }

        i = inext

        if (i == n || extractAbove(INode, packedVals(i), FieldMaskRShifts) != uinode) {
          tMI(0, (itree * nnodes) + inode) = brfeat
          tMI(1, (itree * nnodes) + inode) = bthreshold
          tMI(2, (itree * nnodes) + inode) = bCat(0)
          if (isLastIteration || bgain <= 0f) {
            tMI(3, (itree * nnodes) + inode) = 1
          }
          bgain = NegativeInfinityF; bthreshold = NegativeInfinityI; brfeat = -1;
        }
    }

  }

  def getGain(catCountsSoFar : FMat, catCounts : FMat) : Float = {
    val topCountsSoFar = catCounts - catCountsSoFar
    val topTots = sum(topCountsSoFar, 2)(0)
    val botTots = sum(catCountsSoFar, 2)(0)
    var totTots = sum(catCounts, 2)(0)
    var topFract = 0f
    var botFract = 0f
    if (totTots > 0f) {
      botFract = botTots/ totTots
      topFract = 1f - botFract
    }
    val totalImpurity = getImpurity(catCounts, totTots)
    val topImpurity = getImpurity(topCountsSoFar, topTots)
    val botImpurity = getImpurity(catCountsSoFar, botTots)
    val infoGain = totalImpurity - (topFract * topImpurity) - (botFract * botImpurity)
    infoGain
  }

  def getImpurity(countsSoFar : FMat, totCounts : Float) : Float = {
    var impurity = 0f
    var i = 0
    while (i < countsSoFar.length) {
      var p = 0f
      if (totCounts > 0f) {
        p = countsSoFar(i)/ totCounts
      }
      var plog : Float = 0f
      if (p != 0) {
        plog = scala.math.log(p).toFloat
      }
      impurity = impurity + (-1f * p * plog)
      i += 1
    }
    impurity
  }

  // treeArray - 0) Threshold 1) 
  def updateTreesArray(outv : IMat, outf : IMat, outg : FMat, outc : IMat, treesArray : IMat, fL : IMat) {
    val (maxgs, rows) : (FMat, IMat) = maxi2(outg , 1)
    val cols = irow(0->outg.ncols)
    val bestInds = rows + cols * outg.nrows 
    val boutv = outv(bestInds)
    val boutf = outf(bestInds)
    val boutc = outc(bestInds)
    var i = 0
    while (i < bestInds.length) {
      if (maxgs(i) != -1e7) {
        treesArray(1, i) = boutc(i)
        if (maxgs(i) > 0f) {
          treesArray(2, i) = boutf(i)
          treesArray(0, i) = boutv(i)
        } else {
          treesArray(0, i) = -1 * boutv(i)
        }
      }
      i += 1
    }
  }

  // val treesMetaInt = fdata.izeros(4, (ntrees * nnodes)) // irfeat, threshold, cat, isLeaf
  // treesMetaInt(2, 0->treesMetaInt.ncols) = (ncats) * iones(1, treesMetaInt.ncols)
  // treesMetaInt(3, 0->treesMetaInt.ncols) = (-1) * iones(1, treesMetaInt.ncols)
  def updateTreeDataa(outv : IMat, outf : IMat, outg : FMat, outc : IMat, treeData : IMat, fL : IMat) {
    val (maxgs, bestSamps) : (FMat, IMat) = maxi2(outg , 1)
    val toUpdateMask = IMat(maxgs > -1e7f)
    // val toUpdateInds = inds *@ toUpdateMask
    // treeData    
    var c = 0
    while (c < outg.ncols) {
      var bestg = -1e7; var bestf = -1; var bestv = -1; var bestc = -1 
      var r = 0
      while (r < outg.nrows) {
        if (outg(r, c) > bestg) {
          bestg = outg(r, c)
          bestf = outf(r, c)
          bestv = outv(r, c)
          bestc = outc(r, c)
        }
        r += 1
      }
      if (bestg != -1e7) {
          treeData(2, c) = bestc
          if (bestg > 0f) {
            treeData(0, c) = bestf
            treeData(1, c) = bestv
          } else {
            treeData(3, c) = 1
          }
        }
      c += 1
    }
    // // println("treeData")
    // // println(treeData)
  }

  def treeStepss(tn : IMat, fd : FMat, fL : IMat, tMI : IMat, depth : Int, ncats : Int, isLastIteration : Boolean, useGPU : Boolean) {
    if (useGPU) {
        // CUMACH.treesteps(Pointer trees, Pointer feats, Pointer tpos, Pointer otpos, int nrows, int ncols, int ns, int tstride, int ntrees, int tdepth);
        val gtn = GIMat(tn)

        // CUMACH.treesteps(Pointer trees, Pointer feats, Pointer tpos, Pointer otpos, int nrows, int ncols, int ns, int tstride, int ntrees, int tdepth);
        gtn.free; 

      } else {
        // tic
        treeSteps(tn, fd, fL, tMI, depth, ncats, isLastIteration) 
        // val t1 = toc
        // println("TreeSteps took: " + t1)
      }
  }

  def treeSteps(tn : IMat, fd : FMat, fL : IMat, tMI : IMat, depth : Int, ncats : Int, isLastIteration : Boolean)  {
    val nnodes = (math.pow(2, depth).toInt)
    val nfeats = fd.nrows
    val nitems = fd.ncols
    val ntrees = tn.nrows
    var icol = 0
    while (icol < nitems) {
      var itree = 0
      while (itree < ntrees) {
        val inode = tn(itree, icol)
        if (isLastIteration) {
          val cat = tMI(2, itree * nnodes + inode)
          tn(itree, icol) = math.min(cat, ncats)
        } else if (tMI(3, itree * nnodes + inode) > 0) {
          // Do nothing
        } else {
          val ifeat : Int = tMI(0, itree * nnodes + inode)
          val vfeat : Float = fd(ifeat, icol)
          val ivfeat = vfeat
          val ithresh = tMI(1, itree * nnodes + inode)
          if (ivfeat > ithresh) {
            tn(itree, icol) = 2 * tn(itree, icol) + 2
          } else {
            tn(itree, icol) = 2 * tn(itree, icol) + 1
          }
        }
        itree += 1
      }
      icol += 1
    }
  }

  def treeSearch(ntn : IMat, fd : FMat, fL : IMat, tMI : IMat, depth : Int, ncats : Int) {
    var d = 0
    while (d < depth) {
      treeSteps(ntn, fd, fL, tMI, depth, ncats, d==(depth - 1)) 
      d += 1
    }
  }

  // creats new fd
  def scaleFD(fd : FMat, fb : FMat, nifeats : Int) : IMat = {
    val scaleFactor = (fd - fb(?, 0)) / max(1e-4f, (fb(?, 1) - fb(?, 0)))
    IMat(min(nifeats, scaleFactor * nifeats))
  }

  def scaleFDD(fd : Mat, fb : Mat, nifeats : Int) : Mat = {
    val scaleFactor = (fd - fb(?, 0))/ max(1e-4f, (fb(?, 1) - fb(?, 0)))
    min(nifeats, scaleFactor * nifeats)
  }

  // expects a to be n * t
  // returns out (n * numCats)
  def accumG(a : Mat, dim : Int, numBuckets : Int)  : Mat = {
    (dim, a) match {
      case (1, aMat : IMat) => {
        // col by col
        null
      }
      case (2, aMat : IMat) => {
        val iData = (icol(0->aMat.nrows) * iones(1, aMat.ncols)).data
        val i = irow(iData) // a
        val j = irow(aMat.data) // aMat(?) creates column
        val ij = i on j
        val out = accum(ij.t, 1, null, a.nrows, scala.math.max(a.ncols, numBuckets))
        out
      }
    }
  }

  def voteForBestCategoriesAcrossTrees(treeCatsT : Mat, numCats : Int) : Mat = {
    val accumedTreeCatsT = accumG(treeCatsT, 2, numCats + 1)
    var bundle : (Mat, Mat) = null
    (accumedTreeCatsT) match {
      case (acTCT : IMat) => {
        bundle = maxi2(acTCT, 2)
      }
    }
    val majorityVal = bundle._1
    val majorityIndicies = bundle._2
    majorityIndicies.t
  }
  
  def countC(ind:Array[Long]):Int = {
    val n = ind.length
    var count = math.min(1, n)
    var i = 1
    while (i < n) {
      if (ind(i) != ind(i-1)) {
        count += 1
      }
      i += 1
    }
    return count
  }
  
  def makeC(ind:Array[Long], out:Array[Long], counts:Array[Float]) {
    val n = ind.length
    var cc = 0
    var group = 0
    var i = 1
    while (i <= n) {
      cc += 1
      if (i == n || ind(i) != ind(i-1)) {
        out(group) = ind(i-1)
        counts(group) = cc
        group += 1
        cc = 0
      }
      i += 1
    }
  }
  
  def mergeC(ind1:Array[Long], counts1:Array[Float], ind2:Array[Long], counts2:Array[Float]):Int = {
    var count = 0
    val n1 = counts1.length
    val n2 = counts2.length
    var i1 = 0
    var i2 = 0
    while (i1 < n1 || i2 < n2) {
      // // println("n1: " + n1 + " i1: " + i1)
      // // println("n2: " + n2 + " i2: " + i2)
      if (i1 >= n1 || ( i2 < n2 && ind2(i2) < ind1(i1) )) {
        count += 1
        i2 += 1
      } else if (i2 >= n2 || ( i1 < n1 && ind1(i1) < ind2(i2))) {
        count += 1
        i1 += 1
      } else {
        count += 1
        i1 += 1
        i2 += 1
      }
    }
    return count
  }
  
  def mergeV(ind1:Array[Long], counts1:Array[Float], ind2:Array[Long], counts2:Array[Float], ind3:Array[Long], counts3:Array[Float]):Int = {
    var count = 0
    val n1 = counts1.length
    val n2 = counts2.length
    var i1 = 0
    var i2 = 0
    while (i1 < n1 || i2 < n2) {
      if (i1 >= n1 || ( i2 < n2 && ind2(i2) < ind1(i1) )) {
        ind3(count) = ind2(i2)
        counts3(count) = counts2(i2)
        count += 1
        i2 += 1
      } else if (i2 >= n2 || ( i1 < n1 && ind1(i1) < ind2(i2))) {
        ind3(count) = ind1(i1)
        counts3(count) = counts1(i1)
        count += 1
        i1 += 1
      } else {
        ind3(count) = ind1(i1)
        counts3(count) = counts1(i1) + counts2(i2)
        count += 1
        i1 += 1
        i2 += 1
      }
    }
    return count
  }
  
}