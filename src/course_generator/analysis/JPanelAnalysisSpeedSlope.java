/*
 * Course Generator
 * Copyright (C) 2016 Pierre Delore
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package course_generator.analysis;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartMouseEvent;
import org.jfree.chart.ChartMouseListener;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.AxisLocation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.entity.ChartEntity;
import org.jfree.chart.entity.XYItemEntity;
import org.jfree.chart.panel.CrosshairOverlay;
import org.jfree.chart.plot.Crosshair;
import org.jfree.chart.plot.DatasetRenderingOrder;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYAreaRenderer;
import org.jfree.chart.renderer.xy.XYDotRenderer;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import com.sun.xml.txw2.output.IndentingXMLStreamWriter;

import course_generator.CgData;
import course_generator.TrackData;
import course_generator.dialogs.frmSaveSSCurve;
import course_generator.settings.CgSettings;
import course_generator.utils.CgConst;
import course_generator.utils.Utils;

public class JPanelAnalysisSpeedSlope extends JPanel {
	private static final long serialVersionUID = 4187021605744043506L;
	private JFreeChart chart = null;
	private XYSeriesCollection datasetSpeedSlopePoint = null;
	private XYSeriesCollection datasetSpeedSlopeLine = null;
	private ResourceBundle bundle;
	private JToolBar toolBar;
	private JButton btSpeedSlopeSave;
	private JPanel jPanelSpeedSlopeInfo;
	private JLabel lbSpeedSlopeInfoSpeed;
	private JLabel lbSpeedSlopeInfoSlope;
	private ChartPanel ChartPanelSpeedSlope;
	private Crosshair xCrosshair;
	private TrackData track = null;
	private CgSettings settings = null;
	private JButton btSpeedSlopeCorr;
	private JButton btSpeedSlopeFilter;
	private JButton btSpeedSlopeUpperPercentile;

	private static final double UPPER_PERCENTILE = 0.80;
	private static final double ANALYSIS_SLOPE_WINDOW_M = 40.0;
	private static final double SPEED_FILTER_WINDOW_M = 30.0;
	private static final double MIN_BUCKET_DISTANCE_M = 50.0;
	private static final int MIN_BUCKET_SAMPLES = 3;
	private static final double LOCAL_PERCENTILE_HALF_WIDTH = 3.0;
	private static final double LOCAL_PERCENTILE_SIGMA = 1.5;
	private static final double MIN_LOCAL_PERCENTILE_DISTANCE_M = 200.0;
	private static final int MIN_LOCAL_PERCENTILE_SAMPLES = 8;
	private static final double MIN_ANALYSIS_SLOPE = -50.0;
	private static final double MAX_ANALYSIS_SLOPE = 50.0;

	private double lastX = 0.0;
	private double lastY = 0.0;

	public JPanelAnalysisSpeedSlope(CgSettings settings) {
		super();
		this.settings = settings;
		bundle = java.util.ResourceBundle.getBundle("course_generator/Bundle");
		datasetSpeedSlopePoint = new XYSeriesCollection();
		datasetSpeedSlopeLine = new XYSeriesCollection();
		chart = CreateChart(datasetSpeedSlopePoint, datasetSpeedSlopeLine);
		initComponents();
	}

	private void initComponents() {
		setLayout(new java.awt.BorderLayout());

		// -- Left - Toolbar
		Create_Toolbar();
		add(toolBar, java.awt.BorderLayout.WEST);

		// -- Bottom - Info
		jPanelSpeedSlopeInfo = new javax.swing.JPanel();
		jPanelSpeedSlopeInfo.setOpaque(true);
		jPanelSpeedSlopeInfo.setBackground(Color.WHITE);
		jPanelSpeedSlopeInfo.setLayout(new GridBagLayout());
		add(jPanelSpeedSlopeInfo, java.awt.BorderLayout.SOUTH);

		// -- Speed/Slope

		// -- Speed
		lbSpeedSlopeInfoSpeed = new javax.swing.JLabel();
		lbSpeedSlopeInfoSpeed.setOpaque(true);
		lbSpeedSlopeInfoSpeed.setBackground(Color.WHITE);
		lbSpeedSlopeInfoSpeed.setText(" " + bundle.getString("JPanelAnalysisSpeedSlope.lbSpeedSlopeInfoSpeed.text")
				+ "=" + Utils.FormatSpeed(0.0, settings.Unit, settings.isPace, true));
		lbSpeedSlopeInfoSpeed.setBorder(javax.swing.BorderFactory.createEtchedBorder());
		Utils.addComponent(jPanelSpeedSlopeInfo, lbSpeedSlopeInfoSpeed, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0,
				GridBagConstraints.WEST, GridBagConstraints.BOTH);

		// -- Slope
		lbSpeedSlopeInfoSlope = new javax.swing.JLabel();
		lbSpeedSlopeInfoSlope.setOpaque(true);
		lbSpeedSlopeInfoSlope.setBackground(Color.WHITE);
		lbSpeedSlopeInfoSlope
				.setText(" " + bundle.getString("JPanelAnalysisSpeedSlope.lbSpeedSlopeInfoSlope.text") + "=0.0% ");
		lbSpeedSlopeInfoSlope.setBorder(javax.swing.BorderFactory.createEtchedBorder());
		Utils.addComponent(jPanelSpeedSlopeInfo, lbSpeedSlopeInfoSlope, 1, 0, 1, 1, 1, 0, 0, 0, 0, 0,
				GridBagConstraints.WEST, GridBagConstraints.VERTICAL);

		// -- Chart Speed/Dist & Time/Dist
		ChartPanelSpeedSlope = new ChartPanel(chart, true /* Properties */, true /* save */, true /* print */,
				false /* zoom */, true /* tooltips */);
		ChartPanelSpeedSlope.setDomainZoomable(false);
		ChartPanelSpeedSlope.setRangeZoomable(false);

		CrosshairOverlay crosshairOverlay = new CrosshairOverlay();

		xCrosshair = new Crosshair(Double.NaN, Color.RED, new BasicStroke(0f));
		xCrosshair.setLabelBackgroundPaint(Color.WHITE);

		crosshairOverlay.addDomainCrosshair(xCrosshair);

		ChartPanelSpeedSlope.addOverlay(crosshairOverlay);
		ChartPanelSpeedSlope.setBackground(new java.awt.Color(255, 0, 51));
		ChartPanelSpeedSlope.addChartMouseListener(new ChartMouseListener() {
			@Override
			public void chartMouseClicked(ChartMouseEvent event) {

				ChartEntity chartentity = event.getEntity();
				if (chartentity instanceof XYItemEntity) {
					XYItemEntity e = (XYItemEntity) chartentity;
					XYDataset d = e.getDataset();
					int s = e.getSeriesIndex();
					int i = e.getItem();
					double x = d.getXValue(s, i);
					double y = d.getYValue(s, i);
					// double y = d.getYValue(s, i);
					xCrosshair.setValue(x);
					// yCrosshair.setValue(y);
					RefreshInfo(x, y);
					// //Refresh the position on the data grid
					// TableMain.setRowSelectionInterval(i, i);
					// Rectangle rect = TableMain.getCellRect(i, 0, true);
					// TableMain.scrollRectToVisible(rect);
					// //Refresh the marker position on the map
					// RefreshCurrentPosMarker(Track.data.get(i).getLatitude(),
					// Track.data.get(i).getLongitude());
				}
			}

			@Override
			public void chartMouseMoved(ChartMouseEvent event) {
			}
		});
		add(ChartPanelSpeedSlope, java.awt.BorderLayout.CENTER);

	}

	private void Create_Toolbar() {
		toolBar = new javax.swing.JToolBar();
		toolBar.setOrientation(javax.swing.SwingConstants.VERTICAL);
		toolBar.setFloatable(false);
		toolBar.setRollover(true);

		// -- Save
		// --------------------------------------------------------------
		btSpeedSlopeSave = new javax.swing.JButton();
		btSpeedSlopeSave.setIcon(Utils.getIcon(this, "save_curve.png", settings.ToolbarIconSize));
		btSpeedSlopeSave.setToolTipText(bundle.getString("JPanelAnalysisSpeedSlope.btSpeedSlopeSave.toolTipText"));
		btSpeedSlopeSave.setFocusable(false);
		btSpeedSlopeSave.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				if (track.data.isEmpty())
					return;

				if (datasetSpeedSlopeLine.getSeriesCount() <= 0)
					return;

				frmSaveSSCurve dlg = new frmSaveSSCurve(settings);
				if (dlg.showDialog()) {
					SaveCurve(dlg.getName(), dlg.getComment());
				}
			}
		});
		toolBar.add(btSpeedSlopeSave);

		btSpeedSlopeCorr = new javax.swing.JButton();
		btSpeedSlopeCorr.setIcon(Utils.getIcon(this, "correction.png", settings.ToolbarIconSize));
		btSpeedSlopeCorr.setToolTipText(bundle.getString("JPanelAnalysisSpeedSlope.btSpeedSlopeCorr.toolTipText"));
		btSpeedSlopeCorr.setFocusable(false);
		btSpeedSlopeCorr.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				btSpeedSlopeCorr.setSelected(!btSpeedSlopeCorr.isSelected());
				Refresh(track, settings);
			}
		});
		toolBar.add(btSpeedSlopeCorr);

		btSpeedSlopeFilter = new javax.swing.JButton();
		btSpeedSlopeFilter.setIcon(Utils.getIcon(this, "speed.png", settings.ToolbarIconSize));
		btSpeedSlopeFilter.setToolTipText(bundle.getString("JPanelAnalysisSpeedSlope.btSpeedSlopeFilter.toolTipText"));
		btSpeedSlopeFilter.setFocusable(false);
		btSpeedSlopeFilter.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				btSpeedSlopeFilter.setSelected(!btSpeedSlopeFilter.isSelected());
				Refresh(track, settings);
			}
		});
		toolBar.add(btSpeedSlopeFilter);

		btSpeedSlopeUpperPercentile = new javax.swing.JButton();
		btSpeedSlopeUpperPercentile.setIcon(Utils.getIcon(this, "chart_curve.png", settings.ToolbarIconSize));
		btSpeedSlopeUpperPercentile
				.setToolTipText(bundle.getString("JPanelAnalysisSpeedSlope.btSpeedSlopeUpperPercentile.toolTipText"));
		btSpeedSlopeUpperPercentile.setFocusable(false);
		btSpeedSlopeUpperPercentile.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				btSpeedSlopeUpperPercentile.setSelected(!btSpeedSlopeUpperPercentile.isSelected());
				// Q80 needs a spatially stable speed signal. Raw point-to-point speed
				// produces extreme upper-tail artefacts, so enable the 30 m filter.
				if (btSpeedSlopeUpperPercentile.isSelected())
					btSpeedSlopeFilter.setSelected(true);
				Refresh(track, settings);
			}
		});
		toolBar.add(btSpeedSlopeUpperPercentile);

		SetText_Toolbar();
	}

	private void SetText_Toolbar() {
		btSpeedSlopeSave.setToolTipText(bundle.getString("JPanelAnalysisSpeedSlope.btSpeedSlopeSave.toolTipText"));
		btSpeedSlopeCorr.setToolTipText(bundle.getString("JPanelAnalysisSpeedSlope.btSpeedSlopeCorr.toolTipText"));
		btSpeedSlopeFilter.setToolTipText(bundle.getString("JPanelAnalysisSpeedSlope.btSpeedSlopeFilter.toolTipText"));
		btSpeedSlopeUpperPercentile
				.setToolTipText(bundle.getString("JPanelAnalysisSpeedSlope.btSpeedSlopeUpperPercentile.toolTipText"));
	}

	/**
	 * Save the generated curve as a standard curve
	 * 
	 * @param name    Name of the curve
	 * @param comment Comment for the curve
	 */
	private void SaveCurve(String name, String comment) {
		int n = datasetSpeedSlopeLine.getSeries(0).getItemCount();

		// -- Save the data in the home directory
		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		try {
			BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(
					new FileOutputStream(Utils.getSelectedCurveFolder(CgConst.CURVE_FOLDER_USER) + name + ".par"));
			XMLStreamWriter writer = new IndentingXMLStreamWriter(
					factory.createXMLStreamWriter(bufferedOutputStream, "UTF-8"));

			writer.writeStartDocument("UTF-8", "1.0");
			writer.writeStartElement("Project");
			Utils.WriteStringToXML(writer, "Name", name);
			Utils.WriteStringToXML(writer, "Comment", comment);
			writer.writeStartElement("Param");
			for (int i = 0; i < n; i++) {
				writer.writeStartElement("Item");
				Utils.WriteStringToXML(writer, "Slope",
						String.format(Locale.ROOT, "%f", datasetSpeedSlopeLine.getSeries(0).getX(i)));

				double speed = datasetSpeedSlopeLine.getSeries(0).getY(i).doubleValue();

				Utils.WriteStringToXML(writer, "Speed", String.format(Locale.ROOT, "%f", speed));

				writer.writeEndElement(); // Item
			}
			writer.writeEndElement(); // Param
			writer.writeEndElement(); // Project
			writer.writeEndDocument();
			writer.flush();
			writer.close();
			bufferedOutputStream.close();

		} catch (XMLStreamException | IOException e) {
			e.printStackTrace();
		}

	}

	private JFreeChart CreateChart(XYDataset dataset1, XYDataset dataset2) {
		JFreeChart chart = ChartFactory.createScatterPlot("",
				// x axis label
				bundle.getString("JPanelAnalysisSpeedSlope.labelX"), // "Slope",
				// y axis label
				bundle.getString("JPanelAnalysisSpeedSlope.labelY"), // "Speed",
				// data
				dataset1, PlotOrientation.VERTICAL, false, // include legend
				true, // tooltips
				false // urls
		);

		chart.setBackgroundPaint(Color.white); // Panel background color
		chart.setAntiAlias(true);

		XYPlot plot = chart.getXYPlot();
		plot.setBackgroundPaint(Color.white);
		plot.setDomainGridlinePaint(Color.gray);
		plot.setRangeGridlinePaint(Color.gray);
		plot.setDomainAxisLocation(AxisLocation.BOTTOM_OR_RIGHT);
		plot.setRangeCrosshairLockedOnData(false);

		XYDotRenderer renderer = new XYDotRenderer();
		renderer.setSeriesPaint(0, new Color(0x6B, 0xB2, 0x00));
		renderer.setDotWidth(2);
		renderer.setDotHeight(2);
		plot.setRenderer(renderer);

		NumberAxis rangeAxis2 = new NumberAxis();
		plot.setRangeAxis(1, rangeAxis2);
		plot.setDataset(1, dataset2);
		plot.setRangeAxis(1, rangeAxis2);
		plot.mapDatasetToRangeAxis(1, 1);

		XYAreaRenderer renderer2 = new XYAreaRenderer();
		renderer2.setSeriesPaint(0, new Color(0x99, 0xff, 0x00, 0x80));
		renderer2.setOutline(true);
		renderer2.setSeriesOutlinePaint(0, new Color(0x80, 0x80, 0x80, 0x80));
		renderer2.setSeriesOutlineStroke(0, new BasicStroke(2.0f));
		plot.setRenderer(1, renderer2);

		/*
		 * StandardXYItemRenderer renderer2 = new StandardXYItemRenderer();
		 * renderer2.setSeriesPaint(0, Color.red); plot.setRenderer(1, renderer2);
		 */

		// -- Select the display order
		plot.setDatasetRenderingOrder(DatasetRenderingOrder.REVERSE);

		return chart;
	}

	private void RefreshInfo(double x, double y) {
		if ((track == null) || (settings == null))
			return;

		lastX = x;
		lastY = y;

		lbSpeedSlopeInfoSpeed.setText(" " + bundle.getString("JPanelAnalysisSpeedSlope.lbSpeedSlopeInfoSpeed.text")
				+ "=" + Utils.FormatSpeed(y, settings.Unit, settings.isPace, true) + " ");

		lbSpeedSlopeInfoSlope.setText(" " + bundle.getString("JPanelAnalysisSpeedSlope.lbSpeedSlopeInfoSlope.text")
				+ "=" + String.format("%1.1f ", x) + "% ");
	}

	/**
	 * Update the Slope/Speed chart
	 */
	public void Refresh(TrackData track, CgSettings settings) {
		if (track == null || track.data.isEmpty())
			return;

		this.track = track;
		this.settings = settings;

		if (datasetSpeedSlopePoint.getSeriesCount() > 0)
			datasetSpeedSlopePoint.removeAllSeries();
		if (datasetSpeedSlopeLine.getSeriesCount() > 0)
			datasetSpeedSlopeLine.removeAllSeries();

		XYPlot plot = chart.getXYPlot();
		plot.clearDomainMarkers();

		XYSeries serie1 = new XYSeries("Speed/Slope (points)");
		XYSeries serie2 = new XYSeries("Speed/Slope (line)");

		/*
		 * Analysis-only slope: calculate grade on a 40 m centred spatial window.
		 * This avoids the strong altitude-quantisation artefact produced by the
		 * historical point-to-point slope calculation. The value is stored in tmp2
		 * so the track's normal slope remains untouched.
		 */
		CalculateAnalysisSlope();

		/*
		 * Analysis-only speed filter. The button now enables a centred 30 m spatial
		 * filter instead of a moving average over five GPS points.
		 */
		SpeedFilter();

		final int bucketCount = 101;
		double[] weightedSum = new double[bucketCount];
		double[] weightedSquareSum = new double[bucketCount];
		double[] firstPassWeight = new double[bucketCount];
		int[] firstPassSamples = new int[bucketCount];
		double[] average = new double[bucketCount];
		double[] standardDeviation = new double[bucketCount];

		List<Double> percentileValues = null;
		List<Double> percentileSlopes = null;
		List<Double> percentileDistances = null;

		if (btSpeedSlopeUpperPercentile.isSelected()) {
			percentileValues = new ArrayList<Double>();
			percentileSlopes = new ArrayList<Double>();
			percentileDistances = new ArrayList<Double>();
		}

		/*
		 * Pass 1: distance-weighted statistics per 1% grade bucket.
		 */
		for (CgData r : track.data) {
			int bucket = GetSlopeBucket(r.tmp2);
			double speed = GetCorrectedAnalysisSpeed(r);
			double weight = r.getDist(CgConst.UNIT_METER);

			if (bucket < 0 || Double.isNaN(speed) || weight <= 0.0)
				continue;

			weightedSum[bucket] += speed * weight;
			weightedSquareSum[bucket] += speed * speed * weight;
			firstPassWeight[bucket] += weight;
			firstPassSamples[bucket]++;

			if (btSpeedSlopeUpperPercentile.isSelected()) {
				percentileValues.add(speed);
				percentileSlopes.add(r.tmp2);
				percentileDistances.add(weight);
			}
		}

		for (int i = 0; i < bucketCount; i++) {
			if (firstPassWeight[i] > 0.0) {
				average[i] = weightedSum[i] / firstPassWeight[i];
				double variance = weightedSquareSum[i] / firstPassWeight[i] - average[i] * average[i];
				standardDeviation[i] = Math.sqrt(Math.max(0.0, variance));
			} else {
				average[i] = Double.NaN;
				standardDeviation[i] = Double.NaN;
			}
		}

		/*
		 * Pass 2 for the historical mean curve: keep values inside mean +/- 1 sigma.
		 * The comparison now uses the same difficulty/fatigue-corrected speed as the
		 * statistics, and the final mean is distance weighted.
		 */
		double[] filteredWeightedSum = new double[bucketCount];
		double[] filteredWeight = new double[bucketCount];
		int[] filteredSamples = new int[bucketCount];

		if (!btSpeedSlopeUpperPercentile.isSelected()) {
			for (CgData r : track.data) {
				int bucket = GetSlopeBucket(r.tmp2);
				double speed = GetCorrectedAnalysisSpeed(r);
				double weight = r.getDist(CgConst.UNIT_METER);

				if (bucket < 0 || Double.isNaN(speed) || weight <= 0.0 || Double.isNaN(average[bucket]))
					continue;

				if (speed <= average[bucket] + standardDeviation[bucket]
						&& speed >= average[bucket] - standardDeviation[bucket]) {
					filteredWeightedSum[bucket] += speed * weight;
					filteredWeight[bucket] += weight;
					filteredSamples[bucket]++;
				}
			}
		}

		double maxspeed = 0.0;

		// Scatter plot: use the same spatial grade used by the curve.
		for (CgData r : track.data) {
			int bucket = GetSlopeBucket(r.tmp2);
			if (bucket < 0)
				continue;

			double displayedSpeed = btSpeedSlopeFilter.isSelected() ? r.tmp1 : r.getSpeed();
			if (displayedSpeed > maxspeed)
				maxspeed = displayedSpeed;
			serie1.add(r.tmp2, displayedSpeed);
		}
		datasetSpeedSlopePoint.addSeries(serie1);

		/*
		 * Result curve. Sparse buckets are intentionally omitted instead of being
		 * extrapolated from a handful of GPS points. A bucket needs at least 50 m of
		 * supporting distance and at least three observations.
		 */
		for (int i = 0; i < bucketCount; i++) {
			double value = Double.NaN;

			if (btSpeedSlopeUpperPercentile.isSelected()) {
				value = SpeedSlopeCurveEstimator.localWeightedPercentile(
						percentileValues, percentileSlopes, percentileDistances,
						i - 50, LOCAL_PERCENTILE_HALF_WIDTH, LOCAL_PERCENTILE_SIGMA,
						UPPER_PERCENTILE, MIN_LOCAL_PERCENTILE_DISTANCE_M,
						MIN_LOCAL_PERCENTILE_SAMPLES);
			} else if (filteredWeight[i] >= MIN_BUCKET_DISTANCE_M
					&& filteredSamples[i] >= MIN_BUCKET_SAMPLES) {
				value = filteredWeightedSum[i] / filteredWeight[i];
			}

			if (!Double.isNaN(value)) {
				if (value > maxspeed)
					maxspeed = value;
				serie2.add(i - 50, value);
			}
		}

		if (maxspeed == 0.0)
			return;

		if (serie2.getItemCount() > 0)
			datasetSpeedSlopeLine.addSeries(serie2);

		ValueAxis axisX = plot.getDomainAxis();
		axisX.setRange(MIN_ANALYSIS_SLOPE, MAX_ANALYSIS_SLOPE);

		ValueAxis axisY = plot.getRangeAxis(0);
		axisY.setRange(0, Math.ceil(maxspeed / 5.0) * 5.0);

		axisY = plot.getRangeAxis(1);
		axisY.setRange(1, Math.ceil(maxspeed / 5.0) * 5.0);
	}

	/**
	 * Return the corrected activity speed used to build the personal curve.
	 * Values <= 1.5 km/h are kept out of the analysis as in the historical code.
	 */
	private double GetCorrectedAnalysisSpeed(CgData r) {
		double speed = btSpeedSlopeFilter.isSelected() ? r.tmp1 : r.getSpeed();
		if (speed <= 1.5)
			return Double.NaN;

		double coeff = 1.0;
		double diff = 1.0;
		if (btSpeedSlopeCorr.isSelected()) {
			coeff = r.getCoeff() / 100.0;
			diff = r.getDiff() / 100.0;
		}

		if (coeff <= 0.0 || diff <= 0.0)
			return Double.NaN;

		return speed / coeff / diff;
	}

	/**
	 * Calculate analysis grade on a centred spatial window. The historical
	 * point-to-point slope remains untouched in CgData.Slope.
	 */
	private void CalculateAnalysisSlope() {
		double totalDistance = track.data.get(track.data.size() - 1).getTotal(CgConst.UNIT_METER);
		double halfWindow = ANALYSIS_SLOPE_WINDOW_M / 2.0;

		for (CgData r : track.data) {
			double center = r.getTotal(CgConst.UNIT_METER);
			double startDistance = Math.max(0.0, center - halfWindow);
			double endDistance = Math.min(totalDistance, center + halfWindow);
			double span = endDistance - startDistance;

			if (span < ANALYSIS_SLOPE_WINDOW_M / 2.0) {
				r.tmp2 = Double.NaN;
				continue;
			}

			double startElevation = InterpolateElevationAtDistance(startDistance);
			double endElevation = InterpolateElevationAtDistance(endDistance);
			r.tmp2 = (endElevation - startElevation) / span * 100.0;
		}
	}

	/**
	 * Linear interpolation of the active elevation profile at a cumulative distance.
	 */
	private double InterpolateElevationAtDistance(double targetDistance) {
		if (targetDistance <= 0.0)
			return track.data.get(0).getElevation(CgConst.UNIT_METER);

		int last = track.data.size() - 1;
		double lastDistance = track.data.get(last).getTotal(CgConst.UNIT_METER);
		if (targetDistance >= lastDistance)
			return track.data.get(last).getElevation(CgConst.UNIT_METER);

		int upper = FindFirstPointAfterDistance(targetDistance);
		int lower = Math.max(0, upper - 1);

		CgData a = track.data.get(lower);
		CgData b = track.data.get(upper);
		double da = a.getTotal(CgConst.UNIT_METER);
		double db = b.getTotal(CgConst.UNIT_METER);

		if (db <= da)
			return b.getElevation(CgConst.UNIT_METER);

		double ratio = (targetDistance - da) / (db - da);
		return a.getElevation(CgConst.UNIT_METER)
				+ ratio * (b.getElevation(CgConst.UNIT_METER) - a.getElevation(CgConst.UNIT_METER));
	}

	/**
	 * Find the first point whose cumulative distance is strictly greater than the
	 * requested distance.
	 */
	private int FindFirstPointAfterDistance(double distance) {
		int low = 0;
		int high = track.data.size() - 1;

		while (low < high) {
			int mid = (low + high) >>> 1;
			if (track.data.get(mid).getTotal(CgConst.UNIT_METER) <= distance)
				low = mid + 1;
			else
				high = mid;
		}
		return low;
	}

	/**
	 * Convert an analysis slope to the 1% bucket (-50..+50). Values outside that
	 * range are ignored rather than collapsed into the +/-50% bins.
	 */
	private int GetSlopeBucket(double slope) {
		if (Double.isNaN(slope) || slope < MIN_ANALYSIS_SLOPE || slope > MAX_ANALYSIS_SLOPE)
			return -1;
		if (slope == MAX_ANALYSIS_SLOPE)
			return 100;
		return (int) Math.floor(slope) + 50;
	}

	/**
	 * Centred 30 m spatial speed filter. It calculates the effective speed over the
	 * window from travelled distance and segment times, rather than averaging a
	 * fixed number of GPS samples.
	 */
	private void SpeedFilter() {
		double totalDistance = track.data.get(track.data.size() - 1).getTotal(CgConst.UNIT_METER);
		double halfWindow = SPEED_FILTER_WINDOW_M / 2.0;

		for (int i = 0; i < track.data.size(); i++) {
			CgData current = track.data.get(i);
			double center = current.getTotal(CgConst.UNIT_METER);
			double startDistance = Math.max(0.0, center - halfWindow);
			double endDistance = Math.min(totalDistance, center + halfWindow);

			int firstSegmentEnd = FindFirstPointAfterDistance(startDistance);
			if (firstSegmentEnd < 1)
				firstSegmentEnd = 1;

			double windowDistance = 0.0;
			double windowTime = 0.0;

			for (int j = firstSegmentEnd; j < track.data.size(); j++) {
				CgData previous = track.data.get(j - 1);
				CgData segmentEnd = track.data.get(j);

				double segmentStartDistance = previous.getTotal(CgConst.UNIT_METER);
				double segmentEndDistance = segmentEnd.getTotal(CgConst.UNIT_METER);

				if (segmentStartDistance >= endDistance)
					break;

				double overlapStart = Math.max(startDistance, segmentStartDistance);
				double overlapEnd = Math.min(endDistance, segmentEndDistance);
				double overlapDistance = overlapEnd - overlapStart;

				if (overlapDistance <= 0.0)
					continue;

				double segmentSpeed = segmentEnd.getSpeed();
				if (segmentSpeed <= 0.0)
					continue;

				windowDistance += overlapDistance;
				windowTime += overlapDistance / (segmentSpeed / 3.6);
			}

			if (windowTime > 0.0)
				current.tmp1 = windowDistance / windowTime * 3.6;
			else
				current.tmp1 = current.getSpeed();
		}
	}

	public void ChangeLang() {
		bundle = java.util.ResourceBundle.getBundle("course_generator/Bundle");
		chart.getXYPlot().getDomainAxis(0).setAttributedLabel(bundle.getString("JPanelAnalysisSpeedSlope.labelX")); // X
		chart.getXYPlot().getRangeAxis(0).setAttributedLabel(bundle.getString("JPanelAnalysisSpeedSlope.labelY")); // Y
		RefreshInfo(lastX, lastY);
		SetText_Toolbar();
	}
}
