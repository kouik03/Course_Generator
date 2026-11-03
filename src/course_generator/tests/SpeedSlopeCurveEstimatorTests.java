package course_generator.tests;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import course_generator.analysis.SpeedSlopeCurveEstimator;

public class SpeedSlopeCurveEstimatorTests {

	@Test
	public void percentileInterpolatesUpperEnvelope() {
		assertEquals(4.4,
				SpeedSlopeCurveEstimator.percentile(Arrays.asList(2.0, 3.0, 4.0, 5.0), 0.80),
				0.0001);
	}

	@Test
	public void percentileHandlesSingleValueAndEmptyList() {
		assertEquals(3.5,
				SpeedSlopeCurveEstimator.percentile(Collections.singletonList(3.5), 0.80),
				0.0001);
		assertEquals(true,
				Double.isNaN(SpeedSlopeCurveEstimator.percentile(Collections.<Double>emptyList(), 0.80)));
	}

	@Test(expected = IllegalArgumentException.class)
	public void percentileRejectsOutOfRangePercentile() {
		SpeedSlopeCurveEstimator.percentile(Arrays.asList(1.0, 2.0), 1.1);
	}

	@Test
	public void weightedPercentileUsesDistanceRatherThanPointCount() {
		assertEquals(5.0,
				SpeedSlopeCurveEstimator.weightedPercentile(
						Arrays.asList(2.0, 3.0, 4.0, 5.0),
						Arrays.asList(1.0, 1.0, 1.0, 7.0),
						0.80),
				0.0001);
	}

	@Test(expected = IllegalArgumentException.class)
	public void weightedPercentileRejectsMismatchedLists() {
		SpeedSlopeCurveEstimator.weightedPercentile(
				Arrays.asList(1.0, 2.0),
				Collections.singletonList(1.0),
				0.80);
	}
	@Test
	public void localWeightedPercentileUsesNeighboringSlopesAndMinimumSupport() {
		assertEquals(6.0,
				SpeedSlopeCurveEstimator.localWeightedPercentile(
						Arrays.asList(4.0, 5.0, 6.0, 20.0),
						Arrays.asList(9.0, 10.0, 11.0, 20.0),
						Arrays.asList(100.0, 100.0, 100.0, 100.0),
						10.0, 3.0, 1.5, 0.80, 200.0, 3),
				0.0001);

		assertEquals(true,
				Double.isNaN(SpeedSlopeCurveEstimator.localWeightedPercentile(
						Arrays.asList(4.0, 5.0),
						Arrays.asList(10.0, 11.0),
						Arrays.asList(20.0, 20.0),
						10.0, 3.0, 1.5, 0.80, 200.0, 3)));
	}

}
