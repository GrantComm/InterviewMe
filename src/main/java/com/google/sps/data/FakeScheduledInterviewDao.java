// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.sps.data;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.CompositeFilter;
import com.google.appengine.api.datastore.Query.CompositeFilterOperator;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.api.datastore.Query.SortDirection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** Mimics accessing Datastore to support managing ScheduledInterview entities. */
public class FakeScheduledInterviewDao implements ScheduledInterviewDao {
  // data is the fake database
  public Map<Long, ScheduledInterview> data;

  /** Initializes the fields for ScheduledInterviewDatastoreDAO. */
  public FakeScheduledInterviewDao() {
    data = new HashMap<Long, ScheduledInterview>();
  }

  /**
   * Retrieves a scheduledInterviewEntity from storage and returns it as a ScheduledInterview
   * object.
   */
  @Override
  public Optional<ScheduledInterview> get(long id) {
    if (data.containsKey(id)) {
      return Optional.of(data.get(id));
    }
    return Optional.empty();
  }

  /**
   * Returns a list, sorted by start time, of all scheduled ScheduledInterview objects whose
   * startTime is between minTime and maxTime.
   */
  public List<ScheduledInterview> getInRange(Instant minTime, Instant maxTime) {
    TimeRange range = new TimeRange(minTime, maxTime);
    List<ScheduledInterview> scheduledInterviewsInRange = new ArrayList<>();
    List<ScheduledInterview> scheduledInterviews = new ArrayList<ScheduledInterview>(data.values());
    for (ScheduledInterview scheduledInterview : scheduledInterviews) {
      if (range.contains(scheduledInterview.when())) {
        scheduledInterviewsInRange.add(scheduledInterview);
      }
    }
    scheduledInterviewsInRange.sort(
        (ScheduledInterview s1, ScheduledInterview s2) -> {
          if (s1.when().start().equals(s2.when().start())) {
            return 0;
          }
          if (s1.when().start().isBefore(s2.when().start())) {
            return -1;
          }
          return 1;
        });
    return scheduledInterviewsInRange;
  }

  /**
   * Returns a list, sorted by start time, of all ScheduledInterview objects ranging from minTime to
   * maxTime that are for the selected position and do not already have a shadow.
   */
  public List<ScheduledInterview> getForPositionWithoutShadowInRange(
      Job position, Instant minTime, Instant maxTime) {
    List<ScheduledInterview> interviewsInRange = getInRange(minTime, maxTime);
    Set<ScheduledInterview> notValidInterviews = new HashSet<ScheduledInterview>();
    for (ScheduledInterview interview : interviewsInRange) {
      if (!interview.position().equals(position) || !interview.shadowId().equals("")) {
        notValidInterviews.add(interview);
      }
    }
    interviewsInRange.removeAll(notValidInterviews);
    return interviewsInRange;
  }

  /**
   * Retrieves all scheduledInterview entities from storage that involve a particular user and
   * returns them as a list of ScheduledInterview objects in the order in which they occur.
   */
  @Override
  public List<ScheduledInterview> getForPerson(String userId) {
    List<ScheduledInterview> relevantInterviews = new ArrayList<>();
    List<ScheduledInterview> scheduledInterviews = new ArrayList<ScheduledInterview>(data.values());
    scheduledInterviews.sort(
        (ScheduledInterview s1, ScheduledInterview s2) -> {
          if (s1.when().start().equals(s2.when().start())) {
            return 0;
          }
          if (s1.when().start().isBefore(s2.when().start())) {
            return -1;
          }
          return 1;
        });

    for (ScheduledInterview scheduledInterview : scheduledInterviews) {
      if (userId.equals(scheduledInterview.interviewerId())
          || userId.equals(scheduledInterview.intervieweeId())
          || userId.equals(scheduledInterview.shadowId())) {
        relevantInterviews.add(scheduledInterview);
      }
    }
    return relevantInterviews;
  }

  /**
   * Returns a list of all scheduledInterviews ranging from minTime to maxTime of a user in the
   * order in which they occur.
   */
  @Override
  public List<ScheduledInterview> getScheduledInterviewsInRangeForUser(
      String userId, Instant minTime, Instant maxTime) {
    TimeRange range = new TimeRange(minTime, maxTime);
    List<ScheduledInterview> scheduledInterviews = getForPerson(userId);
    List<ScheduledInterview> scheduledInterviewsInRange = new ArrayList<ScheduledInterview>();
    for (ScheduledInterview scheduledInterview : scheduledInterviews) {
      if (range.contains(scheduledInterview.when())) {
        scheduledInterviewsInRange.add(scheduledInterview);
      }
    }
    return scheduledInterviewsInRange;
  }

  /** Creates a ScheduledInterview Entity and stores it. */
  @Override
  public void create(ScheduledInterview scheduledInterview) {
    long generatedId = new Random().nextLong();
    ScheduledInterview storedScheduledInterview =
        ScheduledInterview.create(
            generatedId,
            scheduledInterview.when(),
            scheduledInterview.interviewerId(),
            scheduledInterview.intervieweeId(),
            scheduledInterview.meetLink(),
            scheduledInterview.position(),
            scheduledInterview.shadowId());
    data.put(generatedId, storedScheduledInterview);
  }

  /** Updates an entity. */
  @Override
  public void update(ScheduledInterview scheduledInterview) {
    data.put(scheduledInterview.id(), scheduledInterview);
  }

  /** Deletes an entity. */
  @Override
  public void delete(long id) {
    data.remove(id);
  }
}
