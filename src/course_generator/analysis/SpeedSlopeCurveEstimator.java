/*
 * Course Generator
 * Copyright (C) 2016 Pierre Delore
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package course_generator.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Statistical helpers used to estimate a speed/slope curve from an activity.
 */
public final class SpeedSlopeCurveEstimator {

	private static class WeightedValue {
		double value;
		double weight;

		WeightedValue(double value, double weight) {
			this.value = value;
			this.weight = weight;
		}
	}

	private SpeedSlopeCurveEstimator() {
	}

	/**
	 * Return an interpolated percentile from the supplied values.
	 *
	 * @param values     Values to analyse. The input list is not modified.
	 * @param percentile Percentile in the [0, 1] range.
	 * @return Percentile value, or {@link Double#NaN} when the list is empty.
	 */
	public static double percentile(List<Double> values, double percentile) {
		if (values == null || values.isEmpty())
			return Double.NaN;

		if (percentile < 0.0 || percentile > 1.0)
			throw new IllegalArgumentException("percentile must be between 0 and 1");

		List<Double> sorted = new ArrayList<Double>(values);
		Collections.sort(sorted);

		if (sorted.size() == 1)
			return sorted.get(0);

		double rank = percentile * (sorted.size() - 1);
		int lower = (int) Math.floor(rank);
		int upper = (int) Math.ceil(rank);

		if (lower == upper)
			return sorted.get(lower);

		double weight = rank - lower;
		return sorted.get(lower) * (1.0 - weight) + sorted.get(upper) * weight;
	}

	/**
	 * Return a distance-weighted percentile. Using segment distance as the weight
	 * prevents slow portions from receiving more influence only because a GPS device
	 * recorded more time samples there.
	 *
	 * @param values     Values to analyse.
	 * @param weights    Non-negative weights associated with each value.
	 * @param percentile Percentile in the [0, 1] range.
	 * @return Weighted percentile, or {@link Double#NaN} for empty input.
	 */
	public static double weightedPercentile(List<Double> values, List<Double> weights, double percentile) {
		if (values == null || weights == null || values.isEmpty())
			return Double.NaN;
		if (values.size() != weights.size())
			throw new IllegalArgumentException("values and weights must have the same size");
		if (percentile < 0.0 || percentile > 1.0)
			throw new IllegalArgumentException("percentile must be between 0 and 1");

		List<WeightedValue> sorted = new ArrayList<WeightedValue>(values.size());
		double totalWeight = 0.0;
		for (int i = 0; i < values.size(); i++) {
			double weight = weights.get(i);
			if (weight < 0.0)
				throw new IllegalArgumentException("weights must be non-negative");
			if (weight > 0.0) {
				sorted.add(new WeightedValue(values.get(i), weight));
				totalWeight += weight;
			}
		}

		if (sorted.isEmpty() || totalWeight <= 0.0)
			return percentile(values, percentile);

		Collections.sort(sorted, new Comparator<WeightedValue>() {
			@Override
			public int compare(WeightedValue a, WeightedValue b) {
				return Double.compare(a.value, b.value);
			}
		});

		double targetWeight = percentile * totalWeight;
		double cumulativeWeight = 0.0;
		for (WeightedValue item : sorted) {
			cumulativeWeight += item.weight;
			if (cumulativeWeight >= targetWeight)
				return item.value;
		}

		return sorted.get(sorted.size() - 1).value;
	}
	/**
	 * Return a local distance-weighted percentile around a target slope. Samples
	 * farther from the target slope receive a Gaussian penalty, which prevents
	 * adjacent 1% buckets from behaving as unrelated populations.
	 *
	 * @return Local percentile value, or NaN when local support is insufficient.
	 */
	public static double localWeightedPercentile(List<Double> values, List<Double> slopes, List<Double> distances,
			double targetSlope, double halfWidth, double sigma, double percentile,
			double minSupportDistance, int minSamples) {
		if (values == null || slopes == null || distances == null)
			return Double.NaN;
		if (values.size() != slopes.size() || values.size() != distances.size())
			throw new IllegalArgumentException("values, slopes and distances must have the same size");
		if (halfWidth <= 0.0 || sigma <= 0.0)
			throw new IllegalArgumentException("halfWidth and sigma must be positive");

		List<Double> localValues = new ArrayList<Double>();
		List<Double> localWeights = new ArrayList<Double>();
		double supportDistance = 0.0;
		int samples = 0;

		for (int i = 0; i < values.size(); i++) {
			double distance = distances.get(i);
			double slopeDelta = Math.abs(slopes.get(i) - targetSlope);
			if (distance <= 0.0 || slopeDelta > halfWidth)
				continue;

			double gaussian = Math.exp(-0.5 * Math.pow(slopeDelta / sigma, 2.0));
			double weight = distance * gaussian;
			if (weight <= 0.0)
				continue;

			localValues.add(values.get(i));
			localWeights.add(weight);
			supportDistance += distance;
			samples++;
		}

		if (supportDistance < minSupportDistance || samples < minSamples)
			return Double.NaN;

		return weightedPercentile(localValues, localWeights, percentile);
	}

}
