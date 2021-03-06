/*==========================================================================
 * Copyright (c) 2014 Pivotal Software Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright
 * notices and license terms. Your use of these subcomponents is subject to
 * the terms and conditions of the subcomponent's license, as noted in the
 * LICENSE file.
 *==========================================================================
 */

package com.pivotal.gfxd.demo.services;

import com.pivotal.gfxd.demo.TimeSlice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.support.JdbcDaoSupport;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * @author Jens Deppe
 */
@Component("predictionService")
public class PredictionService extends JdbcDaoSupport {

  private static final Logger LOG = LoggerFactory.getLogger(
      PredictionService.class.getName());

  private static final String LOAD_AVG_OVERALL_QUERY =
      "select total_load, event_count from load_averages where weekday = ? and time_slice >= ? and time_slice < ?";

  private static final String OPERATIONAL_AVG_OVERALL_QUERY =
      "select value from raw_sensor where weekday = ? and time_slice >= ? and time_slice < ?";

  @Autowired
  public PredictionService(DataSource dataSource) {
    super.setDataSource(dataSource);
  }

  /**
   * Return the predicted load for a given timestamp and time slice. The
   * slice determines the time window into which the timestamp will fall.
   *
   * @param timestamp timestamp in seconds
   * @param interval
   * @return the predicted load
   */
  public float predictedLoad(long timestamp, TimeSlice.Interval interval) {

    float median = 0;
    float currentLoad = 0;

    TimeSlice slice = new TimeSlice(timestamp, interval);
    TimeSlice sliceNext = slice.shift(1);
    TimeSlice slicePast = slice.shift(-1);

    try {
      float[] lastDays = new float[3];
      // Find the average for the last 3 days
      for (int i = 1; i < 4; i++) {
        // Weekdays start at 1 so subtract 1 in order to be able to do modulo
        // arithmetic. Then add 1 at the end again.
        int pastDay = (sliceNext.getWeekday() - 1 - i) % 7;
        // Also, java modulo arithmetic returns negative if the dividend is negative
        if (pastDay < 0) {
          pastDay += 7;
        }
        pastDay += 1;
        lastDays[i - 1] =
            getHistoricalAverageLoad(pastDay, sliceNext.getIntervalStart(),
                sliceNext.getIntervalEnd());
      }
      median = median(lastDays);

      currentLoad = getCurrentAverageLoad(slicePast.getWeekday(),
          slicePast.getIntervalStart(), slicePast.getIntervalEnd());

    } catch (SQLException e) {
      e.printStackTrace();
    }

    return (median + currentLoad) / 2;
  }

  public float currentLoad(long timestamp, TimeSlice.Interval interval) {
    TimeSlice slice = new TimeSlice(timestamp, interval);
    try {
      return getAverageLoad(OPERATIONAL_AVG_OVERALL_QUERY,
          slice.getWeekday(), slice.getIntervalStart(), slice.getIntervalEnd(), 0);
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return 0;
  }

  private float median(float[] values) {
    Arrays.sort(values);
    int len = values.length;
    if (len % 2 == 1) {
      return values[len / 2 + 1];
    } else {
      return (values[len / 2] + values[len / 2 + 1]) / 2;
    }
  }

  private float getHistoricalAverageLoad(int weekDay, int intervalStart,
      int intervalEnd) throws SQLException {
    return getAverageLoad(LOAD_AVG_OVERALL_QUERY,
        weekDay, intervalStart, intervalEnd, 2);
  }

  private float getCurrentAverageLoad(int weekDay, int intervalStart,
      int intervalEnd) throws SQLException {
    return getAverageLoad(OPERATIONAL_AVG_OVERALL_QUERY,
        weekDay, intervalStart, intervalEnd, 0);
  }

  private static class MyRCH implements RowCallbackHandler {
    float load = 0;
    int count = 0;
    int rowWithCount;

    public MyRCH(int rowWithCount) {
      this.rowWithCount = rowWithCount;
    }

    @Override
    public void processRow(ResultSet rs) throws SQLException {
      while (rs.next()) {
        load += rs.getFloat(1);
        if (rowWithCount > 0) {
          count += rs.getInt(rowWithCount);
        } else {
          count += 1;
        }
      }
    }

    public float getLoad() {
      return load;
    }

    public int getCount() {
      return count;
    }
  }

  private float getAverageLoad(String sql, int weekDay, int intervalStart,
      int intervalEnd, int rowWithCount) throws SQLException {

    JdbcTemplate template = getJdbcTemplate();
    Object[] params = {weekDay, intervalStart, intervalEnd};

    MyRCH rch = new MyRCH(rowWithCount);
    template.query(sql, params, rch);

    LOG.debug("--->>> weekday=" + weekDay + " start=" + intervalStart +
        " end=" + intervalEnd + " load=" + rch.getLoad() + " count=" + rch.getCount());

    return rch.getLoad() / rch.getCount();
  }
}
