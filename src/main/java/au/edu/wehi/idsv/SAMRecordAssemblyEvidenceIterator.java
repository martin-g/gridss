package au.edu.wehi.idsv;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.CloserUtil;

import java.util.Iterator;
import java.util.List;

import au.edu.wehi.idsv.visualisation.TrackedBuffer;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;

public class SAMRecordAssemblyEvidenceIterator extends AbstractIterator<SAMRecordAssemblyEvidence> implements CloseableIterator<SAMRecordAssemblyEvidence>, TrackedBuffer {
	private final ProcessingContext processContext;
	private final AssemblyEvidenceSource source;
	private final Iterator<SAMRecord> it;
	private final List<Iterator<SAMRecord>> rit;
	private final List<SequentialRealignedBreakpointFactory> factory;
	private boolean includeBothBreakendsOfSpanningAssemblies;
	public SAMRecordAssemblyEvidenceIterator(
			ProcessingContext processContext,
			AssemblyEvidenceSource source,
			Iterator<SAMRecord> it,
			List<Iterator<SAMRecord>> realignedIt,
			boolean includeBothBreakendsOfSpanningAssemblies) {
		this.processContext = processContext;
		this.source = source;
		this.it = it;
		this.rit = realignedIt;
		this.factory = realignedIt != null ? new SequentialRealignedBreakpointFactory(Iterators.peekingIterator(this.rit)) : null;
		this.includeBothBreakendsOfSpanningAssemblies = includeBothBreakendsOfSpanningAssemblies;
	}
	private SAMRecordAssemblyEvidence buffer = null;
	@Override
	protected SAMRecordAssemblyEvidence computeNext() {
		if (buffer != null) {
			SAMRecordAssemblyEvidence r = buffer;
			buffer = null;
			return r;
		}
		while (it.hasNext()) {
			SAMRecord record = it.next();
			SAMRecordAssemblyEvidence evidence = AssemblyFactory.hydrate(source, record);
			if (factory != null && !(evidence instanceof DirectedBreakpoint)) {
				RealignmentParameters rp = evidence.getEvidenceSource().getContext().getRealignmentParameters();
				SAMRecord realigned = factory.findAssociatedSAMRecord(evidence,
						rp.requireRealignment && 
						rp.shouldRealignBreakend(evidence));
				evidence = AssemblyFactory.incorporateRealignment(processContext, evidence, realigned);
			}
			if (includeBothBreakendsOfSpanningAssemblies && evidence.isSpanningAssembly()) {
				buffer = ((SmallIndelSAMRecordAssemblyEvidence)evidence).asRemote();
			}
			if (evidence != null && !evidence.isReferenceAssembly()) {
				return evidence;
			}
		}
		return endOfData();
	}
	@Override
	public void close() {
		CloserUtil.close(it);
		for (Iterator<SAMRecord> i : rit) {
			CloserUtil.close(i);
		}
	}
	@Override
	public void setTrackedBufferContext(String context) {
		if (factory != null) {
			factory.setTrackedBufferContext(context);
		}
	}
	@Override
	public List<NamedTrackedBuffer> currentTrackedBufferSizes() {
		if (factory != null) {
			return factory.currentTrackedBufferSizes();
		}
		return ImmutableList.of();
	}
}
