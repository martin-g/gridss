package au.edu.wehi.idsv.sam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

import au.edu.wehi.idsv.IntermediateFilesTest;
import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMRecord;

public class SAMFileUtilTest extends IntermediateFilesTest {
	@Test
	public void merge_should_merge_in_order() throws IOException {
		File input2 = testFolder.newFile("input2.bam");
		input2.delete();
		createBAM(input, SortOrder.coordinate,
				Read(0, 1, "1M"),
				Read(0, 3, "1M"),
				Read(0, 5, "1M"));
		createBAM(input2, SortOrder.coordinate,
				Read(0, 2, "1M"),
				Read(0, 4, "1M"),
				Read(0, 6, "1M"));
		SAMFileUtil.merge(ImmutableList.of(input, input2), output);
		List<SAMRecord> list = getRecords(output);
		assertEquals(6, list.size());
		assertTrue(Ordering.from(SortOrder.coordinate.getComparatorInstance()).isOrdered(list));
	}
}