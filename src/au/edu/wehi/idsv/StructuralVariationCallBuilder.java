package au.edu.wehi.idsv;

import java.util.List;

import au.edu.wehi.idsv.vcf.VcfAttributes;

import com.google.common.collect.Lists;

public class StructuralVariationCallBuilder {
	private final ProcessingContext processContext;
	private final EvidenceSource source;
	private final BreakendSummary call;
	private final List<SoftClipEvidence> scList = Lists.newArrayList();
	private final List<NonReferenceReadPair> nrpList = Lists.newArrayList();
	private final List<VariantContextDirectedBreakpoint> assList = Lists.newArrayList();
	private int referenceReadCount = -1;
	private int referenceSpanningPairCount = -1;
	public StructuralVariationCallBuilder(ProcessingContext processContext, EvidenceSource source, BreakendSummary call) {
		this.processContext = processContext;
		this.source = source;
		this.call  = call;
	}
	public StructuralVariationCallBuilder evidence(DirectedEvidence evidence) {
		if (evidence == null) throw new NullPointerException();
		if (evidence instanceof SoftClipEvidence) {
			scList.add((SoftClipEvidence)evidence);
		} else if (evidence instanceof VariantContextDirectedBreakpoint) {
			assList.add((VariantContextDirectedBreakpoint)evidence);
		} else if (evidence instanceof NonReferenceReadPair) {
			nrpList.add((NonReferenceReadPair)evidence);
		} else {
			throw new RuntimeException(String.format("Unknown evidence type %s", evidence.getClass()));
		}
		if (!call.overlaps(evidence.getBreakendSummary())) {
			throw new IllegalArgumentException(String.format("Sanity check failure: Evidence %s does not provide support for call at %s", evidence.getBreakendSummary(), call));
		}
		return this;
	}
	public VariantContextDirectedBreakpoint make() {
		VariantContextDirectedBreakpointBuilder builder = createBuilder();
		
		EvidenceMetrics m = new EvidenceMetrics();
		double oeaMapq = 0, dpMapq = 0;
		for (NonReferenceReadPair e : nrpList) {
			m.add(e.getBreakendSummary().evidence);
			if (e.getRemoteReferenceIndex() == -1) {
				oeaMapq = Math.max(e.getBreakendSummary().evidence.get(VcfAttributes.UNMAPPED_MATE_TOTAL_MAPQ), oeaMapq);
			} else {
				dpMapq = Math.max(e.getBreakendSummary().evidence.get(VcfAttributes.DISCORDANT_READ_PAIR_TOTAL_MAPQ), dpMapq);
			}
		}
		for (SoftClipEvidence e : scList) {
			m.add(e.getBreakendSummary().evidence);
		}
		for (VariantContextDirectedBreakpoint e : assList) {
			m.add(e.getBreakendSummary().evidence);
		}
		builder
			.evidence(m)
			.id(getID())
			.attribute(VcfAttributes.REFERENCE_READ_COUNT.attribute(), referenceReadCount)
			.attribute(VcfAttributes.REFERENCE_SPANNING_READ_PAIR_COUNT.attribute(), referenceSpanningPairCount);
		return new VariantContextDirectedBreakpoint(processContext, source, builder.make());
	}
	public StructuralVariationCallBuilder referenceReads(int count) {
		referenceReadCount = count;
		return this;
	}
	public StructuralVariationCallBuilder referenceSpanningPairs(int count) {
		referenceSpanningPairCount = count;
		return this;
	}
	private String getID() {
		StringBuilder sb = new StringBuilder("call");
		sb.append(processContext.getDictionary().getSequence(call.referenceIndex).getSequenceName());
		sb.append(':');
		sb.append(call.start);
		if (call.end != call.start) {
			sb.append('-');
			sb.append(call.end);
		}
		sb.append(call.direction == BreakendDirection.Forward ? 'f' : 'b');
		if (call instanceof BreakpointSummary) {
			BreakpointSummary loc = (BreakpointSummary)call;
			sb.append(processContext.getDictionary().getSequence(loc.referenceIndex2).getSequenceName());
			sb.append(':');
			sb.append(loc.start2);
			if (loc.end2 != loc.start2) {
				sb.append('-');
				sb.append(loc.end2);
			}
			sb.append(loc.direction2 == BreakendDirection.Forward ? 'f' : 'b');
		}
		return sb.toString();
	}
	private VariantContextDirectedBreakpointBuilder createBuilder() {
		VariantContextDirectedBreakpointBuilder builder;
		VariantContextDirectedBreakpoint ass = bestAssembly();
		SoftClipEvidence sce = bestSoftclip();
		if (ass != null) {
			builder = new VariantContextDirectedBreakpointBuilder(processContext, source, ass);
		} else if (sce != null) {
			builder = new VariantContextDirectedBreakpointBuilder(processContext, source)
				.breakend(sce.getBreakendSummary(), sce.getUntemplatedSequence());
		} else {
			builder = new VariantContextDirectedBreakpointBuilder(processContext, source)
				.breakend(call, "");
		}
		return builder;
	}
	private SoftClipEvidence bestSoftclip() {
		if (scList.size() == 0) return null;
		SoftClipEvidence best = scList.get(0);
		for (SoftClipEvidence sce : scList) {
			if (sce.getBreakendSummary() instanceof BreakpointSummary
					&& !(best.getBreakendSummary() instanceof BreakpointSummary)) {
				best = sce;
			} else if (sce.getBreakendSummary().getClass() == best.getBreakendSummary().getClass()) {
				if (sce.getBreakendSummary() instanceof BreakpointSummary) {
					// both BreakpointSummary
					if (sce.getSoftClipLength() < best.getSoftClipLength()) {
						// take the longest soft clip
						best = sce;
					}
				} else {
					// both BreakendSummary
					if (sce.getSoftClipLength() > best.getSoftClipLength()) {
						best = sce;
					}
				}
			}
		}
		return best;
	}
	private VariantContextDirectedBreakpoint bestAssembly() {
		if (assList.size() == 0) return null;
		VariantContextDirectedBreakpoint best = assList.get(0);
		for (VariantContextDirectedBreakpoint a : assList) {
			if (a.getPhredScaledQual() > best.getPhredScaledQual()) {
				best = a;
			}
		}
		return best;
	}
}