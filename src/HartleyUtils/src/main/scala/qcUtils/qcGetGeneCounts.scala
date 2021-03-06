package qcUtils


import net.sf.samtools._

import internalUtils.Reporter._;
import internalUtils.stdUtils._;
import internalUtils.fileUtils._;
import internalUtils.commandLineUI._;
import internalUtils.commonSeqUtils._;
import internalUtils.genomicAnnoUtils._;
import internalUtils.GtfTool._;
import scala.collection.JavaConversions._;

import internalUtils.genomicUtils._;

import scala.collection.immutable.TreeSet; 
import internalUtils.optionHolder._;
import scala.collection.GenMap;

object qcGetGeneCounts {

  /*
  def readGtf(stranded : Boolean, gtffile : String , geneCounts : scala.collection.mutable.HashMap[String,Int]) : GenomicArrayOfSets[String] = {
    report("reading Gtf: " + gtffile + "\n","note");
    val geneArray : GenomicArrayOfSets[String] = GenomicArrayOfSets[String](stranded);
    val gtfReader = GtfReader.getGtfReader(gtffile, stranded, true, "\\s+");
    var lineCt = 0;
    
    for(gtfLine <- gtfReader){
      lineCt = lineCt + 1;
      readGtfLine(geneArray, gtfLine, geneCounts);
    }
    report("done.\nRead " + lineCt + " gtf lines. Found " + geneCounts.size + " genes.\n","note");
    reportln("Memory usage: " + MemoryUtil.memInfo,"note");    
    return geneArray;
  }
  
  def readGtfLine(geneArray : GenomicArrayOfSets[String], gtfLine : GtfLine, geneCounts : scala.collection.mutable.HashMap[String,Int]) {
    if(gtfLine.featureType == GtfCodes.EXON_TYPE_CODE){
      val geneID = gtfLine.getAttributeOrDie(GtfCodes.GENE_ID_ATTRIBUTE_KEY)
      //reportln("found exon for: " + geneID, "note");
      geneCounts(geneID) = 0;
      geneArray.addSpan(new GenomicInterval(gtfLine.chromName, gtfLine.strand, gtfLine.start - 1, gtfLine.end), geneID);
    }
  }*/
  
  
  
  def calculateGeneBodyCoverage(geneAssignment : String, r1 : SAMRecord, r2 : SAMRecord, geneBodyCoverageMap : GenMap[String, Array[Int]], intervalMap : Map[String, Vector[TreeSet[GenomicInterval]]]){
    if(geneAssignment != "_no_feature" && geneAssignment != "_ambiguous"){
      intervalMap.get(geneAssignment) match {
        case Some(intervalVector) => {
          //val intervalVector = intervalMap(geneAssignment);
          val intervalSet = helper_findReadIntervalCoverage(r1,intervalVector) ++ helper_findReadIntervalCoverage(r2,intervalVector);
          val intervalCountArray = geneBodyCoverageMap(geneAssignment);
      
          for(i <- intervalSet){
            intervalCountArray(i) += 1;
          }
        }
        case None => {
          //Do nothing!
        }
      }
    } // Else do nothing!
  } 
  
  private def helper_findReadIntervalCoverage(r : SAMRecord, intervalVector : Vector[TreeSet[GenomicInterval]]) : Set[Int] = {
    return r.getAlignmentBlocks.iterator.foldLeft( Set[Int]() )( (sofar, currBlock) => {
      val (start,end) = (currBlock.getReferenceStart() - 1, currBlock.getReferenceStart - 1 + currBlock.getLength());
      sofar ++ helper_findSpanIntervalCoverage(start,end,intervalVector);
    });
  }
  
  private def helper_findSpanIntervalCoverage(start : Int, end : Int, intervalVector : Vector[TreeSet[GenomicInterval]]) : Set[Int] = {
    intervalVector.zip(0 until intervalVector.size).filter( (z) => {
      val (ts,i) = z;
      ts.exists( (iv) => iv.overlaps(start,end) );
    }).map((z) => z._2).toSet ;
  }
  
  
  /*
   * Note that intervalBreaks must begin with 0.0 and end with 1.0!
   */

   /*
   * FIX ME FOR STRANDEDNESS!!!!
   */
  def makeGeneIntervalMap(intervalBreaks : Seq[Double], stdGeneArray : GenomicArrayOfSets[String], strandedGeneArray : GenomicArrayOfSets[String]) : Map[String, Vector[TreeSet[GenomicInterval]]] = {
    //val initialMap = geneSet.foldLeft( new scala.collection.immutable.HashMap[String,  TreeSet[GenomicInterval] ]() )((soFar,curr) =>{
    //  soFar + ((curr, new TreeSet[GenomicInterval]() ));
    //})
    val geneMap = helper_calculateGeneAssignmentMap_strict(stdGeneArray, strandedGeneArray);
    reportln("making makeGeneIntervalMap for geneBody calculations. Found: " + geneMap.size + " acceptable genes for gene-body analysis.","debug");
    
    val geneLengths = geneMap.map((cg) => {
      val currGene : String = cg._1;
      val currIvSet : TreeSet[GenomicInterval] = cg._2;
      (currGene, currIvSet.foldLeft(0)((sum,curr) => sum + (curr.end - curr.start) ));
    });
    val geneStrands = geneMap.map((cg) => {
      val (currGene, currIvSet) = cg;
      ((currGene,currIvSet.head.strand));
    })
    
    val out = geneMap.foldLeft(Map[String, Vector[TreeSet[GenomicInterval]]]())((sofar,cg) => {
      val currGene : String = cg._1;
      val currGeneLen : Int = geneLengths(currGene);
      if(geneStrands(currGene) == '+') sofar + ((currGene, helper_calculateBreakMap(cg, currGeneLen, intervalBreaks)));
      else                             sofar + ((currGene, helper_calculateBreakMap(cg, currGeneLen, intervalBreaks).reverse));
    });
    return out;
  }
  
  def makeGeneBodyCoverageCountArrays(intervalCount : Int, geneSet : Iterable[String] ) : scala.collection.mutable.Map[String,Array[Int]] = {
    val out : scala.collection.mutable.AnyRefMap[String,Array[Int]] = scala.collection.mutable.AnyRefMap[String,Array[Int]]();
    
    geneSet.foreach( geneID => {
      out += (geneID, new Array[Int](intervalCount));
    })
    out.repack();
    
    return out;
    
    //return geneSet.map((geneID : String) => {
    //  (geneID, new Array[Int](intervalCount));
    //}).toMap;
  }
   
  private def helper_calculateGeneAssignmentMap_strict(stdGeneArray : GenomicArrayOfSets[String], strandedGeneArray : GenomicArrayOfSets[String]) : Map[String, TreeSet[GenomicInterval]] = {

    val badGeneSet = stdGeneArray.getSteps.foldLeft(Set[String]())( (soFar, curr) =>{
      val (iv, geneSet) = curr;
      if(geneSet.size > 1){
        soFar ++ geneSet.toSet;
      } else {
        soFar;
      }
    });
    //val allGeneSet =  geneArray.getSteps.foldLeft(Set[String]())( (soFar, curr) =>{
    //  soFar ++ curr._2.toSet;
    //});
    
    reportln("helper_calculateGeneAssignmentMap_strict. Found: " + strandedGeneArray.getValueSet.size + " genes in the supplied annotation.","debug");
    reportln("helper_calculateGeneAssignmentMap_strict. Found: " + badGeneSet.size + " genes with ambiguous segments.","debug");
    
    val buf = strandedGeneArray.getSteps.foldLeft(  Map[String, TreeSet[GenomicInterval] ]() )( (soFar, curr) => {
      val currIv = curr._1;
      val currGeneSet = curr._2;
      if(currGeneSet.size == 1){
        val currGene = currGeneSet.head;
        if(! badGeneSet.contains(currGene)){
          soFar.get(currGene) match {
            case Some(ts : TreeSet[GenomicInterval]) => soFar + ((currGene, ts + currIv ));
            case None => soFar + ((currGene, TreeSet[GenomicInterval](currIv) ));
          }
        } else {
          soFar;
        }
      } else {
        soFar;
      }
    })
    reportln("helper_calculateGeneAssignmentMap_strict. Found: " + buf.size + " genes after first-pass filtering","debug");

    return buf.filter( (curr) => {
      val (geneID, geneTree) = curr;
      if(geneTree.size == 0) false;
      else {
        geneTree.forall( (c) => geneTree.head.chromName == c.chromName && geneTree.head.strand == c.strand );
      }
    });
  }
  
  /*
   * Old note: FIX ME FOR STRANDEDNESS!!!!
   * FIXED.
   */
  private def helper_calculateBreakMap( cg : (String, TreeSet[GenomicInterval]), currGeneLen : Int,  intervalBreaks : Seq[Double]) : Vector[TreeSet[GenomicInterval]] = {
    val currGene : String = cg._1;
    val currIvSet : TreeSet[GenomicInterval] = cg._2;
    val breakPoints : Seq[Int] = intervalBreaks.map((b) => ( b * currGeneLen.toDouble ).floor.toInt);
    val chromName : String = currIvSet.head.chromName;
    val strand : Char = currIvSet.head.strand;
    
    val breakSpans : Seq[(Int,Int)] = breakPoints.zip(breakPoints.tail);
    val geneCoordMap : Seq[(GenomicInterval,(Int,Int))] = currIvSet.scanLeft( (GenomicInterval(chromName,strand,0,0), (0,0)) )( (sofar, iv) =>{
      val currSpan : (Int,Int) = ((iv.start,iv.end));
      val currSpanGeneCoord : (Int,Int) = (sofar._2._2, sofar._2._2 + (currSpan._2 - currSpan._1)  )
      ((iv,currSpanGeneCoord));
    }).toSeq;
    
    return breakSpans.map((currSpan : (Int,Int)) => {
      helper_calculateBreaks(currSpan, geneCoordMap, chromName, strand);
    }).toVector;
  }
  private def helper_calculateBreaks( currSpan : (Int,Int), geneCoordMap : Seq[(GenomicInterval,(Int,Int))], chromName : String, strand : Char ) : TreeSet[GenomicInterval] = {
    val (currSpanStart, currSpanEnd) = currSpan;
    val intersectingSpans : Array[(GenomicInterval, (Int,Int))] = geneCoordMap.filter( pp => {
        val (iv, (gs,ge)) = pp;
        ( gs < currSpanEnd ) && (currSpanStart < ge);
    }).toArray
    if(intersectingSpans.length > 0){
      val headStart = intersectingSpans.head._2._1;
      val lastEnd = intersectingSpans.last._2._2;
      val addToStart = currSpanStart - headStart;
      val subtractFromEnd = lastEnd - currSpanEnd;
      intersectingSpans(0) = (( new GenomicInterval(chromName, strand, intersectingSpans.head._1.start + addToStart, intersectingSpans.head._1.end ), (intersectingSpans.head._2._1 + addToStart, intersectingSpans.head._2._2) ));
      intersectingSpans(intersectingSpans.length - 1) = (( new GenomicInterval(chromName, strand, intersectingSpans.last._1.start, intersectingSpans.last._1.end - subtractFromEnd ), (intersectingSpans.last._2._1, intersectingSpans.last._2._2 - subtractFromEnd) ));      
      
      return intersectingSpans.foldLeft(TreeSet[GenomicInterval]())( (sofar, c) =>{
        sofar + c._1;
      })
    } else {
      return TreeSet[GenomicInterval]();
    }
  }
  
  def writeGeneBodyCoverage_genewise(outfile : String, geneBody_intervalBreaks : Seq[Double], geneCounts : scala.collection.mutable.Map[String,Int], geneBody_CoverageCountArrays : scala.collection.mutable.Map[String,Array[Int]]){
    val writer = openWriterSmart_viaGlobalParam(outfile+".geneBodyCoverage.genewise.txt");
    
    writer.write("GENE_ID	" + geneBody_intervalBreaks.tail.mkString("	")+"\n");
    for(gene <- geneBody_CoverageCountArrays.keys.toVector.sorted){
      val countArray = geneBody_CoverageCountArrays(gene)
      writer.write(gene +"	"+ countArray.mkString("	") + "\n");
    }
    close(writer);
  }
  def debug_writeGeneBodySpans(outfile : String, geneBody_intervalBreaks : Seq[Double], geneBody_CoverageIntervalMap : Map[String, Vector[TreeSet[GenomicInterval]]]){
    val writer = openGzipWriter(outfile+".geneBodyCoverage.DEBUG.intervals.txt.gz");
    writer.write("GENE_ID	" + geneBody_intervalBreaks.tail.mkString("	")+"\n");
    for((gene,treeVector) <- geneBody_CoverageIntervalMap){
      writer.write(gene +"	"+ treeVector.map( (pp) => {
        "["+pp.map(iv => iv.start +"," + iv.end).mkString("][")+"]";
      }).mkString("	") + "\n");
    }
    close(writer);
  }
  
  
  
  val default_coverageLevelThresholds = Seq(("1.bottomHalf",0.5),("2.upperMidQuartile",0.75),("3.75to90",0.9),("4.high",1.0));
  
  //UNFINISHED?
  def geneBody_calculateGeneBodyCoverage_summaries(outfile : String, geneBody_intervalBreaks : Seq[Double], coverageLevelThresholds : Seq[(String,Double)], geneBody_CoverageCountArrays : GenMap[String,Array[Int]], geneCounts : scala.collection.mutable.Map[String,Int]){
    val geneBody_IntervalCount = geneBody_intervalBreaks.length - 1;
    val totalGeneBodyCoverage_simple = geneBody_CoverageCountArrays.foldLeft(repToSeq(0,geneBody_IntervalCount))((sofar, currPair) =>{
      (0 until sofar.length).map(i => {
        sofar(i) + currPair._2(i);
      }).toSeq;
    });
    
    val includeGenesSet = geneBody_CoverageCountArrays.keySet;
    val sortedReadCountSeq = geneCounts.toVector.filter( (pair) =>  includeGenesSet.contains(pair._1) && pair._2 > 0).sortBy( (pair) => (pair._2,pair._1) );
    val coverageThresholds = coverageLevelThresholds.map(cl_thresh => (sortedReadCountSeq.size.toDouble * cl_thresh._2).toInt );
    
    reportln("DEBUG NOTE: IncludeGenesSet.size: " + includeGenesSet.size,"debug");
    reportln("DEBUG NOTE: sortedReadCountSeq.size: " + sortedReadCountSeq.size,"debug");
    reportln("DEBUG NOTE: coverageThresholds: "+coverageThresholds.mkString(";")+"","debug");
    val coverageSpans = Seq[(Int,Int)]((0, coverageThresholds.head)) ++ coverageThresholds.zip(coverageThresholds.tail);
    reportln("DEBUG NOTE: coverageSpans: ["+coverageSpans.mkString(";")+"]","debug");
    
    val geneBodyByCoverageLevel =  coverageLevelThresholds.zip(coverageSpans).map(cltpair => {
      val ((coverName, coverLevel), (start,end)) = cltpair;
      
      reportln("DEBUG NOTE:	["+coverName+"]["+coverLevel+"] = ["+start+","+end+"]","debug");
      
      sortedReadCountSeq.slice(start,end).foldLeft(repToSeq(0,geneBody_IntervalCount))((sofar, currPair) => {
        val (geneID, geneCounts) = currPair;
        val cca = geneBody_CoverageCountArrays(geneID);
        sofar.zip(cca).map(p => p._1 + p._2);
      })
    });
    
    val writer = openWriterSmart_viaGlobalParam(outfile+".geneBodyCoverage.by.expression.level.txt");
    writer.write("QUANTILE	" + coverageLevelThresholds.map(_._1).mkString("	") + "\n");
    for(i <- 0 until geneBody_IntervalCount){
      writer.write(geneBody_intervalBreaks.tail(i) + "	" + (0 until coverageLevelThresholds.length).map(j => {
        geneBodyByCoverageLevel(j)(i);
      }).mkString("	") + "\n");
    }
    close(writer);
    
    val geneBody_CoveragePctArrays = sortedReadCountSeq.map(_._1).toList.map( g => {
      val ctArray = geneBody_CoverageCountArrays.get(g).get;
      val cts = ctArray.iterator.toVector;
      val pct = cts.map(_.toDouble / cts.sum.toDouble);
      (g, pct);
    }).toMap;
    
    //calculate by percentile:
    val totalGeneBodyCoverage_PCT = geneBody_CoveragePctArrays.foldLeft(repToSeq(0.toDouble,geneBody_IntervalCount))((sofar, currPair) =>{
      (0 until sofar.length).map(i => {
        sofar(i) + currPair._2(i).toDouble;
      }).toSeq;
    }).map( _ / sortedReadCountSeq.length);
    val geneBodyByCoverageLevel_PCT = coverageLevelThresholds.zip(coverageSpans).map(cltpair => {
      val ((coverName, coverLevel), (start,end)) = cltpair;
      val sliceSize = end - start;
      sortedReadCountSeq.slice(start,end).foldLeft(repToSeq(0.toDouble,geneBody_IntervalCount))((sofar, currPair) => {
        val (geneID, geneCounts) = currPair;
        val cca = geneBody_CoveragePctArrays(geneID);
        sofar.zip(cca).map(p => p._1 + (p._2.toDouble / sliceSize.toDouble));
      })
    });
    val writer2 = openWriterSmart_viaGlobalParam(outfile+".geneBodyCoverage.byExpr.avgPct.txt");
    writer2.write("QUANTILE\t" +"TOTAL\t"+ coverageLevelThresholds.map(_._1).mkString("\t") + "\n");
    for(i <- 0 until geneBody_IntervalCount){
      writer2.write(geneBody_intervalBreaks.tail(i) + "\t" + totalGeneBodyCoverage_PCT(i) +"\t"+ (0 until coverageLevelThresholds.length).map(j => {
        geneBodyByCoverageLevel_PCT(j)(i);
      }).mkString("\t") + "\n");
    }
    close(writer2);
  }
  
  def featuresNearPair(distance : Int, r1 : SAMRecord, r2 : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean, featureArray : GenomicArrayOfSets[String], reverseStrand : Boolean = false) : Set[String] = {
    featuresNearRead(distance,r1,stranded,fr_secondStrand, featureArray) ++ featuresNearRead(distance,r1,stranded,fr_secondStrand, featureArray);
  }
  def featuresNearRead(distance : Int, r : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean, featureArray : GenomicArrayOfSets[String], reverseStrand : Boolean = false) : Set[String] = {
    val readIntervals : Iterator[GenomicInterval] = getExpandedGenomicIntervalsFromRead(distance, r , stranded , fr_secondStrand);
    readIntervals.map((iv) => {
      featureArray.findIntersectingSteps(iv).map(_._2).flatten.toSet
    }).flatten.toSet
  }
  
  def pairIsNearFeatures(distance : Int, r1 : SAMRecord, r2 : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean, featureArray : GenomicArrayOfSets[String]) : Boolean = {
    readIsNearFeatures(distance,r1,stranded,fr_secondStrand, featureArray) || readIsNearFeatures(distance,r1,stranded,fr_secondStrand, featureArray);
  }
  def readIsNearFeatures(distance : Int, r : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean, featureArray : GenomicArrayOfSets[String]) : Boolean = {
    val readIntervals : Iterator[GenomicInterval] = getExpandedGenomicIntervalsFromRead(distance, r , stranded , fr_secondStrand);
    return readIntervals.exists( (iv) => {
      featureArray.findIntersectingSteps(iv).exists(! _._2.isEmpty);
    });
  }
  def getPairFeaturesNear(distance : Int, r1 : SAMRecord, r2 : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean, featureArray : GenomicArrayOfSets[String]): Set[String] = {
    return getPairFeaturesNear(distance, r1,stranded,fr_secondStrand, featureArray) ++ getReadFeatures(r2,stranded,fr_secondStrand, featureArray)
  }
  def getPairFeaturesNear(distance : Int, r : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean, featureArray : GenomicArrayOfSets[String]) : Set[String] = {
    val readIntervals : Iterator[GenomicInterval] = getExpandedGenomicIntervalsFromRead(distance, r , stranded , fr_secondStrand);
    return readIntervals.foldLeft(Set[String]())((setSoFar, iv) => {
      featureArray.findIntersectingSteps(iv).foldLeft(setSoFar)((ssf,featureSet) => {
        ssf ++ featureSet._2;
      })
    })
  }
  
  def getPairFeatures( r1 : SAMRecord, r2 : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean, featureArray : GenomicArrayOfSets[String]): Set[String] = {
    return getReadFeatures(r1,stranded,fr_secondStrand, featureArray) ++ getReadFeatures(r2,stranded,fr_secondStrand, featureArray)
  }
  def getReadFeatures(r : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean, featureArray : GenomicArrayOfSets[String]) : Set[String] = {
    val readIntervals : Iterator[GenomicInterval] = getGenomicIntervalsFromRead(r , stranded , fr_secondStrand);
    return readIntervals.foldLeft(Set[String]())((setSoFar, iv) => {
      featureArray.findIntersectingSteps(iv).foldLeft(setSoFar)((ssf,featureSet) => {
        ssf ++ featureSet._2;
      })
    })
  }
  
}

/************************************************************************************************************************************************************
 * Class:
 */

class qcGetGeneCounts( stranded : Boolean, 
                       fr_secondStrand : Boolean, 
                       anno_holder : qcGtfAnnotationBuilder, 
                       coda : Array[Int], 
                       coda_options : Array[Boolean] , 
                       geneBodyIntervalCount : Int, 
                       calcRPKM : Boolean, writeGenewiseGeneBody : Boolean, writeDESeq : Boolean, writeGeneCounts : Boolean, writeGeneBody : Boolean,
                       writeBiotypeCounts : Boolean,
                       geneKeepFunc : (String => Boolean), calcDetailedGeneCounts : Boolean = false) extends QCUtility[String] {
  
  reportln("> Init GeneCalcs Utility","debug");
  
  val geneArray : GenomicArrayOfSets[String] = anno_holder.geneArray;
  val strandedGeneArray : GenomicArrayOfSets[String] = anno_holder.strandedGeneArray;
  
  val geneBody_IntervalCount : Int = geneBodyIntervalCount;
  val geneBody_intervalBreaks = (0 to geneBody_IntervalCount).map(_.toDouble / geneBody_IntervalCount.toDouble).toSeq
  
  //Major change: fix genebody data for unstranded data:
  val geneBody_CoverageIntervalMap = qcGetGeneCounts.makeGeneIntervalMap(geneBody_intervalBreaks, geneArray, strandedGeneArray);
  //val geneBody_CoverageIntervalMap = qcGetGeneCounts.makeGeneIntervalMap(geneBody_intervalBreaks, strandedGeneArray);
  ////reportln("making geneBody_CoverageIntervalMap for geneBody calculations. Found: " + geneBody_CoverageIntervalMap.size + " acceptable genes.","debug");
  val geneBody_CoverageCountArrays : scala.collection.mutable.Map[String,Array[Int]] = qcGetGeneCounts.makeGeneBodyCoverageCountArrays(geneBody_IntervalCount, geneBody_CoverageIntervalMap.keys);
  
  //val mapLocation_CDS : GenomicArrayOfSets[String]
  val geneArea_cdsArray = anno_holder.qcGetGeneCounts_cdsArray;
  val geneArea_intronsArray = anno_holder.qcGetGeneCounts_intronArray;
  
  //INITIALIZE COUNTERS:
  val geneCounts : scala.collection.mutable.Map[String,Int] = qcGtfAnnotationBuilder.initializeCounter[String](geneArray.getValueSet);
  
  //val utilCounts : scala.collection.mutable.Map[String,Int] = qcGtfAnnotationBuilder.initializeCounter[String](Set("_no_feature","_ambiguous"));
  val geneArea_cdsCounts : scala.collection.mutable.Map[String,Int] = qcGtfAnnotationBuilder.initializeCounter[String](geneArray.getValueSet);
  
  val spanArray : GenomicArrayOfSets[String] = if(calcDetailedGeneCounts){
    anno_holder.qcGetGeneCounts_spanArray;
  } else {
    GenomicArrayOfSets[String](stranded);
  }
  
  //Advanced gene counts:
  val geneArea_intronsCounts : scala.collection.mutable.Map[String,Int] = if(calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](spanArray.getValueSet);
                                                                          else  scala.collection.mutable.Map[String,Int]();
  val geneArea_intronsAmbig : scala.collection.mutable.Map[String,Int] = if(calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](spanArray.getValueSet);
                                                                          else  scala.collection.mutable.Map[String,Int]();
  val geneArea_nearCounts : scala.collection.mutable.Map[String,Int] = if(calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](spanArray.getValueSet);
                                                                          else  scala.collection.mutable.Map[String,Int]();
  val geneArea_nearAmbig : scala.collection.mutable.Map[String,Int] = if(calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](spanArray.getValueSet);
                                                                          else  scala.collection.mutable.Map[String,Int]();
  val geneArea_farCounts : scala.collection.mutable.Map[String,Int] = if(calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](spanArray.getValueSet);
                                                                          else  scala.collection.mutable.Map[String,Int]();
  val geneArea_farAmbig : scala.collection.mutable.Map[String,Int] = if(calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](spanArray.getValueSet);
                                                                          else  scala.collection.mutable.Map[String,Int]();
  
  val geneArea_wrongStrandCts : scala.collection.mutable.Map[String,Int] = if(stranded && calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](geneArray.getValueSet);
                                                                           else  scala.collection.mutable.Map[String,Int]();
  
  val geneArea_wrongStrandAmbig : scala.collection.mutable.Map[String,Int] = if(stranded && calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](geneArray.getValueSet);
                                                                                 else  scala.collection.mutable.Map[String,Int]();
  
  val geneArea_wrongStrandAndIntron : scala.collection.mutable.Map[String,Int] = if(stranded && calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](spanArray.getValueSet);
                                                                                 else  scala.collection.mutable.Map[String,Int]();
  val geneArea_wrongStrandAndIntronAmbig : scala.collection.mutable.Map[String,Int] = if(stranded && calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](spanArray.getValueSet);
                                                                                 else  scala.collection.mutable.Map[String,Int]();
  
  //val geneArea_wrongStrandOrIntron : scala.collection.mutable.Map[String,Int] = if(stranded && calcDetailedGeneCounts)  qcGtfAnnotationBuilder.initializeCounter[String](geneArea_intronsArray.getValueSet);
  //                                                                               else  scala.collection.mutable.Map[String,Int]();
  
  val geneCounts_ambig : scala.collection.mutable.Map[String,Int] = qcGtfAnnotationBuilder.initializeCounter[String](geneArray.getValueSet);
  val geneCounts_utr : scala.collection.mutable.Map[String,Int] = qcGtfAnnotationBuilder.initializeCounter[String](geneArray.getValueSet);
  
  var readNoFeature : Int = 0;
  var readAmbiguous : Int = 0;
  
  var readExonCount : Int = 0;
  var readUtrCount : Int = 0;
  var readCdsCount : Int = 0;
  var readIntronCount : Int = 0;
  var readOneKb : Int = 0;
  var readTenKb : Int = 0;
  var readMiddleOfNowhere : Int = 0;
  
  var detailed_readIntronCount = 0;
  var detailed_readOneKb = 0;
  var detailed_readTenKb = 0;
  var detailed_readMiddleOfNowhere = 0;
  var detailed_strandSwap = 0;
  var detailed_strandSwapIntron = 0;
  
  def runOnReadPair(r1 : SAMRecord, r2 : SAMRecord, readNum : Int) : String = {
    val readGenes = qcGetGeneCounts.getPairFeatures(r1,r2,stranded,fr_secondStrand,geneArray);
    
    if(readGenes.size == 0){
      //utilCounts("_no_feature") += 1;
      readNoFeature += 1;
      
      //val readIntrons = qcGetGeneCounts.getPairFeatures(r1,r2,stranded,fr_secondStrand,geneArea_intronsArray);
      //for(intron <- readIntrons){
      //  geneArea_intronsCounts(intron) += 1;
      //}
      //if(readIntrons.size > 0) readIntronCount += 1;
      if(calcDetailedGeneCounts){
        val intronGeneSet = qcGetGeneCounts.featuresNearPair(0,    r1,r2,stranded,fr_secondStrand,spanArray);
        val nearbyGeneSet = qcGetGeneCounts.featuresNearPair(1000, r1,r2,stranded,fr_secondStrand,spanArray);
        val farGeneSet = qcGetGeneCounts.featuresNearPair(10000,r1,r2,stranded,fr_secondStrand,spanArray);
        
        if(intronGeneSet.isEmpty) detailed_readIntronCount += 1;
        else if(nearbyGeneSet.isEmpty) detailed_readOneKb += 1;
        else if(farGeneSet.isEmpty) detailed_readTenKb += 1;
        else detailed_readMiddleOfNowhere += 1;
        
        if(intronGeneSet.size == 1){
          geneArea_intronsCounts(intronGeneSet.head) += 1;
        } else if(intronGeneSet.size > 1){
          intronGeneSet.foreach((g) => {
            geneArea_intronsAmbig(g) += 1;
          })
        } else if(nearbyGeneSet.size == 1){
          geneArea_nearCounts(nearbyGeneSet.head) += 1;
        } else if(nearbyGeneSet.size > 1){
          nearbyGeneSet.foreach((g) => {
            geneArea_nearAmbig(g) += 1;
          })
        } else if(farGeneSet.size == 1){
          geneArea_farCounts(farGeneSet.head) += 1;
        } else if(farGeneSet.size > 1){
          farGeneSet.foreach((g) => {
            geneArea_farAmbig(g) += 1;
          })
        }
        
        if(stranded){
          val strandSwapAndIntronGeneSet = qcGetGeneCounts.featuresNearPair(0,  r1,r2,stranded,fr_secondStrand,spanArray, reverseStrand = true);
          val strandSwapGeneSet = qcGetGeneCounts.featuresNearPair(0,  r1,r2,stranded,fr_secondStrand,geneArray, reverseStrand = true);
          if(strandSwapGeneSet.size == 1){
            geneArea_wrongStrandCts(strandSwapGeneSet.head) += 1;
            detailed_strandSwap += 1;
          } else if(strandSwapGeneSet.size > 1) {
            strandSwapGeneSet.foreach((g) => {
              geneArea_wrongStrandAmbig(g) += 1;
            })
            detailed_strandSwap += 1;
          } else if(intronGeneSet.isEmpty){
            if(strandSwapAndIntronGeneSet.size == 1){
              geneArea_wrongStrandAndIntron(strandSwapAndIntronGeneSet.head) += 1;
              detailed_strandSwapIntron += 1;
            } else if(strandSwapAndIntronGeneSet.size > 1){
              detailed_strandSwapIntron += 1;
              strandSwapAndIntronGeneSet.foreach((g) => {
                geneArea_wrongStrandAndIntronAmbig(g) += 1;
              })
            }
          }
        }
      }
      if(qcGetGeneCounts.pairIsNearFeatures(0,r1,r2,stranded,fr_secondStrand,geneArea_intronsArray)) readIntronCount += 1;
      else if(qcGetGeneCounts.pairIsNearFeatures(1000,r1,r2,stranded,fr_secondStrand,geneArray)) readOneKb += 1;
      else if(qcGetGeneCounts.pairIsNearFeatures(10000,r1,r2,stranded,fr_secondStrand,geneArray)) readTenKb += 1;
      else readMiddleOfNowhere += 1;
      
      return "_no_feature";
    } else if(readGenes.size > 1){
      //utilCounts("_ambiguous") += 1;
      readAmbiguous += 1;
      for(g <- readGenes){
        geneCounts_ambig(g) += 1;
      }
      return "_ambiguous";
    } else {
      val geneAssignment = readGenes.head
      if( geneKeepFunc(geneAssignment) ){
        geneCounts(geneAssignment) += 1;
        readExonCount += 1;
        val cdsGenes = qcGetGeneCounts.getPairFeatures(r1,r2,stranded,fr_secondStrand,geneArea_cdsArray);
        if(cdsGenes.size == 1){
          geneArea_cdsCounts(geneAssignment) += 1;
          readCdsCount += 1;
        } else {
          geneCounts_utr(geneAssignment) += 1;
          readUtrCount += 1;
        }
      
        //calculateGeneBodyCoverage(geneAssignment : String, r1 : SAMRecord, r2 : SAMRecord, geneBodyCoverageMap : Map[String, Array[Int]], intervalMap : Map[String, Vector[TreeSet[GenomicInterval]]])
        qcGetGeneCounts.calculateGeneBodyCoverage(geneAssignment, r1,r2, geneBody_CoverageCountArrays, geneBody_CoverageIntervalMap);
      }
      return geneAssignment;
    }
  }
  
  //def getReadGenes(r : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean) : Set[String] = {
  //  return qcGetGeneCounts.getReadFeatures(r,stranded,fr_secondStrand,geneArray);
  //}
  
  //var readUtrCount : Int = 0;
  //var readCdsCount : Int = 0;
 // var readIntronCount : Int = 0;
  //var readOneKb : Int = 0;
  //var readTenKb : Int = 0;
 // var readMiddleOfNowhere : Int = 0;
  //def geneArea_CDS_getReadGenes(r : SAMRecord, stranded : Boolean, fr_secondStrand : Boolean) : 
  
  def writeOutput(outfile : String, summaryWriter : WriterUtil){
    
    ////Start with the summary:
    
    ////val writerSummary = openWriter(outfile + ".geneMappingSummary.txt");

    //summaryWriter.write(internalUtils.commonSeqUtils.causeOfDropArrayToStringTabbed(coda, coda_options));
    ////summaryWriter.write("ReadPairs_AmbigGene	"+utilCounts("_ambiguous")+"\n");
    summaryWriter.write("ReadPairs_AmbigGene	"+readAmbiguous+"\n");
    summaryWriter.write("ReadPairs_UniqueGene	"+readExonCount+"\n");
    summaryWriter.write("ReadPairs_UniqueGene_CDS	"+readCdsCount+"\n");
    summaryWriter.write("ReadPairs_UniqueGene_UTR	"+readUtrCount+"\n");
    //summaryWriter.write("ReadPairs_NoGene	"+utilCounts("_no_feature")+"\n");
    summaryWriter.write("ReadPairs_NoGene	"+readNoFeature+"\n");
    summaryWriter.write("ReadPairs_NoGene_Intron	"+readIntronCount+"\n");
    summaryWriter.write("ReadPairs_NoGene_OneKbFromGene	"+readOneKb+"\n");
    summaryWriter.write("ReadPairs_NoGene_TenKbFromGene	"+readTenKb+"\n");
    summaryWriter.write("ReadPairs_NoGene_MiddleOfNowhere	"+readMiddleOfNowhere+"\n");
    
    val nonzero_genes = geneCounts.count( pair => { pair._2 > 0 });
    val zero_genes = geneCounts.size - nonzero_genes;
    summaryWriter.write("Genes_Total	" + geneCounts.size +"\n");
    summaryWriter.write("Genes_WithZeroCounts	" + zero_genes +"\n");
    summaryWriter.write("Genes_WithNonzeroCounts	" + nonzero_genes +"\n");

    
    //close(writerSummary);
    
    //SUMMARY DONE.
    if(writeDESeq){
      val writer2 = openWriterSmart_viaGlobalParam(outfile + ".geneCounts.formatted.for.DESeq.txt");
      for(key <- geneCounts.keys.toVector.sorted){
        writer2.write(key + "	"+geneCounts(key) +"\n");
      }
      //writer2.write("no_feature	"+utilCounts("_no_feature")+"\n")
      //writer2.write("ambiguous	"+utilCounts("_ambiguous")+"\n")
      writer2.write("no_feature	"+readNoFeature+"\n");
      writer2.write("ambiguous	"+readAmbiguous+"\n");
      writer2.write("too_low_aQual	NA\n");
      writer2.write("not_aligned	"+coda(internalUtils.commonSeqUtils.CODA_MATE_UNMAPPED)+"\n");
      writer2.write("alignment_not_unique	"+coda(internalUtils.commonSeqUtils.CODA_NOT_UNIQUE_ALIGNMENT)+"\n");
      close(writer2);
    }
    if(writeGeneCounts){
      val writer = openWriterSmart_viaGlobalParam(outfile + ".geneCounts.txt");
      //writer.write("");
      writer.write("GENEID	COUNT	COUNT_CDS	COUNT_UTR	COUNT_AMBIG_GENE\n");
      for(key <- geneCounts.keys.toVector.sorted){
        writer.write(key + "\t"+geneCounts(key) +"\t"+geneArea_cdsCounts(key)+"\t"+geneCounts_utr(key)+"\t"+geneCounts_ambig(key)+"\n");
      }
      //writer.write("_total_no_feature	"+ utilCounts("_no_feature") + "	0	0	0\n");
      //writer.write("_total_ambiguous	"+ utilCounts("_ambiguous") + "	0	0	0\n");
      writer.write("_total_no_feature\t"+ readNoFeature + "\t0\t0\t0\n");
      writer.write("_total_ambiguous\t"+ readAmbiguous + "\t0\t0\t0\n");
      close(writer);
    }
    if(calcDetailedGeneCounts){
      val writer = openWriterSmart_viaGlobalParam(outfile + ".geneCounts.detailed.txt");
      //writer.write("");
      val titleString = "GENEID\tCOUNT\tCOUNT_CDS\tCOUNT_UTR\tCOUNT_AMBIG_GENE\t"+
                        "intronic\tintronic_AMBIG\tnearby\tnearby_AMBIG\tfar\tfar_AMBIG"+ 
                        (if(stranded){"\twrongStrand\twrongStrand_AMBIG\twrongStrandIntron\twrongStrandIntron_AMBIG"}) + 
                        "\n";
      writer.write(titleString);
      for(key <- geneCounts.keys.toVector.sorted){
        val line = key + "\t"+geneCounts(key) +"\t"+geneArea_cdsCounts(key)+"\t"+geneCounts_utr(key)+"\t"+geneCounts_ambig(key)+
                   "\t"+geneArea_intronsCounts(key)+"\t"+geneArea_intronsAmbig(key)+
                   "\t"+geneArea_nearCounts(key)+"\t"+geneArea_nearAmbig(key)+
                   "\t"+geneArea_farCounts(key)+"\t"+geneArea_farAmbig(key)+
                   (if(stranded){
                     "\t"+geneArea_wrongStrandCts(key)+"\t"+geneArea_wrongStrandAmbig(key)+
                     "\t"+geneArea_wrongStrandAndIntron(key)+"\t"+geneArea_wrongStrandAndIntronAmbig(key)
                   })+
                   "\n"
        writer.write(line);
      }
      summaryWriter.write("ReadPairs_ADV_NoGene_Intron\t" + detailed_readIntronCount +"\n");
      summaryWriter.write("ReadPairs_ADV_NoGene_OneKbFromGene\t" + detailed_readOneKb +"\n");
      summaryWriter.write("ReadPairs_ADV_NoGene_TenKbFromGene\t" + detailed_readTenKb +"\n");
      summaryWriter.write("ReadPairs_ADV_NoGene_MiddleOfNowhere\t" + detailed_readMiddleOfNowhere +"\n");
      summaryWriter.write("ReadPairs_ADV_NoGene_swappedStrand\t" + detailed_strandSwap +"\n");
      summaryWriter.write("ReadPairs_ADV_NoGene_swappedStrandIntron\t" + detailed_strandSwapIntron +"\n");
      
      close(writer);
    }
    
    //delete this later:
    if(writeGeneBody){
      debug_writeGeneBodySpans(outfile);
      if(writeGenewiseGeneBody){
        writeGeneBodyCoverage_genewise(outfile);
      }
      writeGeneBodyCoverage_summaryByExpressionLevel(outfile);
    }
    
    if(writeBiotypeCounts){
      val biotypeMap = anno_holder.geneBiotypeMap;
      val biotypeSet = (biotypeMap.map(_._2).toSet + "UNK");
      
      val writer = openWriterSmart_viaGlobalParam(outfile + ".biotypeCounts.txt");
      writer.write("BIOTYPE\tCOUNT\tCOUNT_AMBIG_GENE\tTOTAL\n");
      
      for(bt <- biotypeSet.toVector.sorted){
        val count =       geneCounts.filter((x : (String,Int)) => biotypeMap(x._1) == bt).map(_._2).sum;
        val ambig = geneCounts_ambig.filter((x : (String,Int)) => biotypeMap(x._1) == bt).map(_._2).sum;
        writer.write(bt+"\t"+count+"\t"+ambig+"\t"+(count+ambig)+"\n");
      }
      writer.close();
    }
    
    if(calcRPKM){
      val writerRPKM = openWriterSmart_viaGlobalParam(outfile + ".FPKM.txt");
      
      val M = readExonCount.toDouble / 1000000.toDouble;
      
      writerRPKM.write("GENEID	FPKM\n");
      for(key <- geneCounts.keys.toSeq.sorted){
        val K = anno_holder.geneLengthMap(key).toDouble / 1000.toDouble;
        val F = geneCounts(key).toDouble;
        val FPKM = (F / K) / M;
        writerRPKM.write(key + "	"+FPKM+"\n");
      }
      
      close(writerRPKM);
    }
  }
  
  def writeGeneBodyCoverage_genewise(outfile :String){
//  def writeGeneBodyCoverage_genewise(outfile : String, geneBody_intervalBreaks : Seq[Double], geneCounts : scala.collection.mutable.Map[String,Int], geneBody_CoverageCountArrays : Map[String,Array[Int]]){
    qcGetGeneCounts.writeGeneBodyCoverage_genewise(outfile, geneBody_intervalBreaks, geneCounts, geneBody_CoverageCountArrays);
  }
  def debug_writeGeneBodySpans(outfile : String){
    qcGetGeneCounts.debug_writeGeneBodySpans(outfile, geneBody_intervalBreaks, geneBody_CoverageIntervalMap);
  }
  
  def writeGeneBodyCoverage_summaryByExpressionLevel(outfile : String){
    //geneBody_calculateGeneBodyCoverage_summaries(outfile : String, geneBody_intervalBreaks : Seq[Double], coverageLevelThresholds : Seq[(String,Double)], geneBody_CoverageCountArrays : Map[String,Array[Int]], geneCounts : scala.collection.mutable.Map[String,Int])
    qcGetGeneCounts.geneBody_calculateGeneBodyCoverage_summaries(outfile, geneBody_intervalBreaks, qcGetGeneCounts.default_coverageLevelThresholds, geneBody_CoverageCountArrays, geneCounts);
  }
  
  def getUtilityName : String = "GeneCalcs";

}
















