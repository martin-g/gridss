#!/bin/bash
#
# Performs targeted extract of fragments overlapping a region of interest
#
# ../scripts/gridss_extract_overlapping_fragments.sh -t $(nproc) --targetvcf COLO829v003T.purple.sv.vcf -o out.example.targeted.bam ~/colo829/COLO829R_dedup.realigned.bam
getopt --test
if [[ ${PIPESTATUS[0]} -ne 4 ]]; then
	echo 'WARNING: "getopt --test"` failed in this environment.' 1>&2
	echo "WARNING: The version of getopt(1) installed on this system might not be compatible with the GRIDSS driver script." 1>&2
fi
set -o errexit -o pipefail -o noclobber -o nounset
last_command=""
current_command=""
trap 'last_command=$current_command; current_command=$BASH_COMMAND' DEBUG
trap 'echo "\"${last_command}\" command completed with exit code $?."' EXIT
#253 forcing C locale for everything
export LC_ALL=C

EX_USAGE=64
EX_NOINPUT=66
EX_CANTCREAT=73
EX_CONFIG=78

workingdir="."
output_bam=""
threads=1
targetbed=""
targetvcf=""
targetmargin=5000
USAGE_MESSAGE="
Usage: gridss_extract_overlapping_fragments.sh [options] --targetbed <target.bed> --targetvcf <target.vcf> [--targetmargin $targetmargin] input.bam

	Extract all alignments of reads overlapping any of the target regions.
	
	This utility is very similar to \"samtools view -L\" but ensures that if
	any alignment record overlaps a region of interest, all alignment records
	with that read name are included in the output regardless of their alignment
	location. This ensure that all records in a read pair or a chimeric alignment
	are jointly included or excluded from the output. This requires a full
	traversal of the output.
	
	--targetbed: BED regions of interest
	--targetvcf: SV VCF contains calls of interest
		If a SV VCF is used as input. Rscript must be on path and the
		StructuralVariantAnnotation package must be installed.
		All SV breakend are considered targets regardless of VCF notation
			- start and end of <DEL> <DUP> <INS> <INV> symbolic alleles,
			- start and end of all indels
			- all <BND> postions
			- SNVs and CNV records are ignored
		The targeted region of breakpoints with microhomology and imprecise
		extends to the bounds defined by CIPOS and CIEND.
	--targetmargin: size of flanking region to include around the targeted region (Default: $targetmargin)
	-o/--output: output BAM. Defaults to adding a .targeted.bam suffix to the input filename
	-t/--threads: number of threads to use. (Default: $threads)
	-w/--workingdir: directory to place intermediate and temporary files. (Default: $workingdir)
"

OPTIONS=r:o:t:w:
LONGOPTS=output:,threads:,workingdir:,targetbed:,targetvcf:,targetmargin:
! PARSED=$(getopt --options=$OPTIONS --longoptions=$LONGOPTS --name "$0" -- "$@")
if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
    # e.g. return value is 1
    #  then getopt has complained about wrong arguments to stdout
	echo "$USAGE_MESSAGE" 1>&2
    exit $EX_USAGE
fi
eval set -- "$PARSED"
while true; do
    case "$1" in	
		-w|--workingdir)
			workingdir="$2"
			shift 2
			;;
		-o|--output)
			output_bam="$2"
			shift 2
			;;
		-t|--threads)
			printf -v threads '%d\n' "$2" 2>/dev/null
			printf -v threads '%d' "$2" 2>/dev/null
			shift 2
			;;
		--targetbed)
			targetbed="$2"
			shift 2
			;;
		--targetvcf)
			targetvcf="$2"
			shift 2
			;;
		--targetmargin)
			printf -v targetmargin '%d\n' "$2" 2>/dev/null
			printf -v targetmargin '%d' "$2" 2>/dev/null
			shift 2
			;;
		--)
			shift
			break
			;;
		*)
			echo "Programming error"
			exit 1
			;;
	esac
done
# $1 is message to write
write_status() {
	echo "$(date): $1" 1>&2
}
if [[ ! -d $workingdir ]] ; then
	mkdir -p $workingdir
	if [[ ! -d $workingdir ]] ; then
		write_status "Unable to create working directory $workingdir"
		exit $EX_CANTCREAT
	fi
fi
if [[ $# -eq 0 ]]; then
	write_status "$USAGE_MESSAGE"
	write_status "Missing input file."
	exit $EX_USAGE
fi
if [[ $# -ne 1 ]]; then
	write_status "$USAGE_MESSAGE"
	write_status "Error: found multiple input files."
	exit $EX_USAGE
fi
if [[ ! -f "$1" ]] ; then
	write_status "$USAGE_MESSAGE"
	write_status "Missing input file."
	exit $EX_NOINPUT
fi
input_bam="$1"
write_status "Using input file \"$input_bam\""
if [[ "$targetbed" != "" ]] ; then
	if [[ "$targetvcf" != "" ]] ; then
		write_status "$USAGE_MESSAGE"
		write_status "--targetbed and --targetvcf are mutually exclusive. Specify only one."
		exit $EX_USAGE
	fi
	if [[ ! -f "$targetbed" ]] ; then
		write_status "$USAGE_MESSAGE"
		write_status "Missing --targetbed file $targetbed"
		exit $EX_NOINPUT
	fi
	write_status "Using region of interest BED file $targetvcf"
else
	if [[ ! -f "$targetvcf" ]] ; then
		write_status "$USAGE_MESSAGE"
		write_status "Missing --targetvcf file $targetvcf"
		exit $EX_NOINPUT
	fi
	write_status "Using SVs in $targetvcf as regions of interest"
fi
##### --output
if [[ "$output_bam" == "" ]] ; then
	output_bam="$1.targeted.bam"
fi
mkdir -p $(dirname $output_bam)
if [[ ! -d $(dirname $output_bam) ]] ; then
	write_status "Unable to create directory for $output_bam for output file."
	exit $EX_CANTCREAT
fi
write_status "Using output file $output_bam"
##### --threads
if [[ "$threads" -lt 1 ]] ; then
	write_status "$USAGE_MESSAGE"
	write_status "Illegal thread count: $threads. Specify an integer thread count using the --threads command line argument"
	exit $EX_USAGE
fi
write_status "Using $threads worker threads."
mkdir -p $workingdir
working_prefix=$workingdir/tmp.$(basename "$output_bam").gridss
target_no_slop_file=$working_prefix.target.no_slop.bed
target_file=$working_prefix.target.bed
script_vcf_to_bed=$working_prefix.vcf2bed.R
rm -f $working_prefix*
if [[ "$targetbed" == "" ]] ; then
	if which Rscript > /dev/null ; then
		write_status "Converting SV VCF to BED of breakpoint positions."
	else 
		write_status "Unable to convert SV VCF to BED. Rscript is not on PATH. "
		write_status "Note that the StructuralVariantAnnotation BioConductor package is also required."
		exit $EX_USAGE
	fi
	rm -f $script_vcf_to_bed
	cat > $script_vcf_to_bed << EOF
suppressPackageStartupMessages(library(StructuralVariantAnnotation))
vcf = readVcf("$targetvcf")
bpgr = breakpointRanges(vcf, unpartneredBreakends=FALSE, inferMissingBreakends=TRUE)
begr = breakpointRanges(vcf, unpartneredBreakends=TRUE)
remove(vcf)
gr = c(bpgr, begr)
remove(begr)
remove(bpgr)
gr = sort(gr, ignore.strand=TRUE)
export(object=gr, con="$target_no_slop_file", format="bed")
EOF
	Rscript "$script_vcf_to_bed"
else
	cp $targetbed $target_no_slop_file
fi
write_status "Extending regions of interest by $targetmargin bp"
# bedtools slop is technically more correct but samtools is happy with
# BED intevals hanging over the start/end of a contig so it doesn't matter
grep -v "^#" $target_no_slop_file | grep -v "^browser" | grep -v "^track" | awk "{OFS=\"\t\"} {print \$1,\$2-$targetmargin,\$3+$targetmargin}" > $target_file

for tool in gridsstools samtools ; do
	if ! which $tool >/dev/null; then
		write_status "Error: unable to find $tool on \$PATH"
		exit $EX_CONFIG
	fi
done
write_status "Extracting reads of interest"
gridsstools extractFragmentsToBam -@ $threads -o $output_bam <(samtools view -M -@ $threads -L $target_file $input_bam | cut -f 1) $input_bam

write_status "Done"
rm -f $working_prefix*
trap - EXIT
exit 0 # success!