package au.edu.wehi.idsv;

import htsjdk.samtools.SAMRecord;

import org.apache.commons.lang3.StringUtils;

import au.edu.wehi.idsv.vcf.VcfAttributes;

/**
 * A read pair that does not support the reference sequence. This can be an OEA, or DP read pair.
 * @author Daniel Cameron
 *
 */
public class NonReferenceReadPair implements DirectedEvidence {
	private final SAMRecord local;
	private final SAMRecord remote;
	private final BreakendSummary location;
	private final SAMEvidenceSource source;
	public NonReferenceReadPair(SAMRecord local, SAMRecord remote, SAMEvidenceSource source) {
		if (local == null) throw new IllegalArgumentException("local is null");
		if (remote == null) throw new IllegalArgumentException("remote is null");
		if (!StringUtils.equals(local.getReadName(), remote.getReadName())) throw new IllegalArgumentException(String.format("Read %s and %s do not match", local.getReadName(), remote.getReadName()));
		if (local.getReadUnmappedFlag()) throw new IllegalArgumentException("local must be mapped");
		if (local.getProperPairFlag()) throw new IllegalArgumentException(String.format("Read %s is flagged as part of a proper pair", local.getReadName()));
		if (remote.getProperPairFlag()) throw new IllegalArgumentException(String.format("Read %s is flagged as part of a proper pair", remote.getReadName()));
		if (source.getMetrics().getMaxFragmentSize() < local.getReadLength()) throw new IllegalArgumentException(String.format("Sanity check failure: read pair %s contains read of length %d when maximum fragment size is %d", local.getReadName(), local.getReadLength(), source.getMetrics().getMaxFragmentSize()));
		this.local = local;
		this.remote = remote;
		this.location = calculateBreakendSummary(local, remote, source.getMetrics().getMaxFragmentSize());
		this.source = source;
	}
	private static EvidenceMetrics calculateOeaMetrics(SAMRecord local, SAMRecord remote) {
		EvidenceMetrics m = new EvidenceMetrics();
		m.set(VcfAttributes.UNMAPPED_MATE_READ_COUNT, 1);
		//m.set(EvidenceAttributes.UNMAPPED_MATE_MAX_MAPQ, local.getMappingQuality());
		m.set(VcfAttributes.UNMAPPED_MATE_TOTAL_MAPQ, local.getMappingQuality());
		return m;
	}
	private static EvidenceMetrics calculateDpMetrics(SAMRecord local, SAMRecord remote) {
		EvidenceMetrics m = new EvidenceMetrics();
		int mapq = Math.min(local.getMappingQuality(), remote.getMappingQuality());
		m.set(VcfAttributes.DISCORDANT_READ_PAIR_COUNT, 1);
		//m.set(EvidenceAttributes.DISCORDANT_READ_PAIR_MAX_MAPQ, mapq);
		m.set(VcfAttributes.DISCORDANT_READ_PAIR_TOTAL_MAPQ, mapq);
		return m;
	}
	/**
	 * Calculates the local breakpoint location
	 * @param local local read
	 * @param remote remote read
	 * @param maxfragmentSize maximum fragment size
	 * @return local {@link BreakendSummary}, without quality information
	 */
	private static BreakendSummary calculateLocalBreakendSummary(SAMRecord local, SAMRecord remote, int maxfragmentSize) {
		BreakendDirection direction = getBreakendDirection(local);
		int positionClosestToBreakpoint;
		int intervalDirection;
		// adds back in any soft-clipped bases
		int intervalExtendedReadDueToLocalClipping;
		int intervalReducedDueToRemoteMapping = 1;
		if (direction == BreakendDirection.Forward) {
			positionClosestToBreakpoint = local.getAlignmentEnd();
			intervalDirection = 1;
			intervalExtendedReadDueToLocalClipping = local.getUnclippedEnd() - local.getAlignmentEnd();
		} else {
			positionClosestToBreakpoint = local.getAlignmentStart();
			intervalDirection = -1;
			intervalExtendedReadDueToLocalClipping = local.getAlignmentStart() - local.getUnclippedStart();
		}
		if (remote != null && !remote.getReadUnmappedFlag()) {
			intervalReducedDueToRemoteMapping = remote.getReadLength();
			// add back in any soft-clipped bases
			if (getBreakendDirection(remote) == BreakendDirection.Forward) {
				intervalReducedDueToRemoteMapping -= remote.getUnclippedEnd() - remote.getAlignmentEnd();
			} else {
				intervalReducedDueToRemoteMapping -= remote.getAlignmentStart() - remote.getUnclippedStart();
			}
		}
		int intervalWidth = maxfragmentSize - local.getReadLength() + intervalExtendedReadDueToLocalClipping - intervalReducedDueToRemoteMapping;
		intervalWidth = Math.min(intervalWidth, pairSeparation(local, remote));
		if (intervalWidth < 0) return null;
		return new BreakendSummary(local.getReferenceIndex(), direction,
				Math.min(positionClosestToBreakpoint, positionClosestToBreakpoint + intervalWidth * intervalDirection),
				Math.max(positionClosestToBreakpoint, positionClosestToBreakpoint + intervalWidth * intervalDirection),
				calculateOeaMetrics(local, remote));
	}
	/**
	 * Determines the separation between discordant reads
	 * @param local
	 * @param remote
	 * @return number possible breakpoints between the read pair mapped in the expected orientation,
	 *  or Integer.MAX_VALUE if placement is not as expected
	 */
	private static int pairSeparation(SAMRecord local, SAMRecord remote) {
		if (local.getReadUnmappedFlag() || remote.getReadUnmappedFlag()) return Integer.MAX_VALUE;
		if (local.getReferenceIndex() != remote.getReferenceIndex()) return Integer.MAX_VALUE;
		// Assuming FR orientation
		if (local.getReadNegativeStrandFlag() == remote.getReadNegativeStrandFlag()) return Integer.MAX_VALUE;
				// <--local
		if ((local.getReadNegativeStrandFlag() && local.getAlignmentStart() > remote.getAlignmentStart())
				// local--> 
				|| (!local.getReadNegativeStrandFlag() && local.getAlignmentStart() < remote.getAlignmentStart())) {
			// only problem with this pair is the fragment size is unexpected
			return Math.max(local.getAlignmentStart(), remote.getAlignmentStart()) - Math.min(local.getAlignmentEnd(), remote.getAlignmentEnd()) - 1;
		}
		return Integer.MAX_VALUE;
	}
	private static BreakendSummary calculateBreakendSummary(SAMRecord local, SAMRecord remote, int maxfragmentSize) {
		if (remote == null || remote.getReadUnmappedFlag()) {
			return calculateLocalBreakendSummary(local, remote, maxfragmentSize);
		} else {
			// discordant because the pairs overlap = no SV evidence 
			if (pairSeparation(local, remote) < 0) return null;
			
			return new BreakpointSummary(
					calculateLocalBreakendSummary(local, remote, maxfragmentSize),
					calculateLocalBreakendSummary(remote, local, maxfragmentSize),
					calculateDpMetrics(local, remote));
		}
	}
	/**
	 * Breakpoint direction the read pair supports relative to the given mapped read.
	 * <p>A forward breakpoint direction indicates that this read pairs supports a breakpoint
	 * after the final mapped based of the locally mapped read.</p>
	 * <p>A backward breakpoint direction indicates that this read pairs supports a breakpoint
	 * before the alignment start position of the locally mapped read.</p>
	 * <p>This method assumes an Illumina FR read pair library preparation.</p>
	 * @return breakpoint direction this read supports
	 */
	public static BreakendDirection getBreakendDirection(SAMRecord read) {
		return read.getReadNegativeStrandFlag() ? BreakendDirection.Backward : BreakendDirection.Forward;
	}
	/**
	 * Mapped read under consideration
	 * @return
	 */
	public SAMRecord getLocalledMappedRead() { return local; }
	/**
	 * Read not supporting the reference placement of the originating fragment 
	 * @return
	 */
	public SAMRecord getNonReferenceRead() { return remote; }
	public int getRemoteReferenceIndex() {
		if (remote == null || remote.getReadUnmappedFlag()) return -1;
		return remote.getReferenceIndex();
	}
	@Override
	public String getEvidenceID() {
		return local.getReadName();
	}
	@Override
	public BreakendSummary getBreakendSummary() {
		return location;
	}
	@Override
	public EvidenceSource getEvidenceSource() {
		return source;
	}
}