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

package com.google.sps.servlets;

import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.gson.Gson;
import com.google.sps.data.Availability;
import com.google.sps.data.AvailabilityDao;
import com.google.sps.data.DatastoreAvailabilityDao;
import com.google.sps.data.DatastorePersonDao;
import com.google.sps.data.DatastoreScheduledInterviewDao;
import com.google.sps.data.EmailSender;
import com.google.sps.data.InterviewPostRequest;
import com.google.sps.data.Person;
import com.google.sps.data.PersonDao;
import com.google.sps.data.ScheduledInterview;
import com.google.sps.data.ScheduledInterviewDao;
import com.google.sps.data.ScheduledInterviewRequest;
import com.google.sps.data.TimeRange;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.io.IOException;
import java.io.BufferedReader;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import java.time.format.DateTimeParseException;

@WebServlet("/scheduled-interviews")
public class ScheduledInterviewServlet extends HttpServlet {

  private ScheduledInterviewDao scheduledInterviewDao;
  private AvailabilityDao availabilityDao;
  private PersonDao personDao;
  private final UserService userService = UserServiceFactory.getUserService();
  private Path emailsPath =
      Paths.get(
          System.getProperty("user.home") + "/InterviewMe/src/main/resources/templates/email");

  @Override
  public void init() {
    init(
        new DatastoreScheduledInterviewDao(),
        new DatastoreAvailabilityDao(),
        new DatastorePersonDao());
  }

  public void init(
      ScheduledInterviewDao scheduledInterviewDao,
      AvailabilityDao availabilityDao,
      PersonDao personDao) {
    this.scheduledInterviewDao = scheduledInterviewDao;
    this.availabilityDao = availabilityDao;
    this.personDao = personDao;
  }

  // Gets the current user's email and returns the ScheduledInterviews for that person.
  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String timeZoneId = request.getParameter("timeZone");
    String userTime = request.getParameter("userTime");
    String userEmail = userService.getCurrentUser().getEmail();
    String userId = getUserId();

    List<ScheduledInterviewRequest> scheduledInterviews =
        scheduledInterviewsToRequestObjects(
            scheduledInterviewDao.getForPerson(userId), timeZoneId, userTime);
    request.setAttribute("scheduledInterviews", scheduledInterviews);
    RequestDispatcher rd = request.getRequestDispatcher("/scheduled-interviews.jsp");
    try {
      rd.forward(request, response);
    } catch (ServletException e) {
      throw new RuntimeException(e);
    }
  }

  // Send the request's contents to Datastore in the form of a new ScheduledInterview object.
  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String intervieweeEmail = userService.getCurrentUser().getEmail();
    String intervieweeId = getUserId();

    InterviewPostRequest postRequest;
    try {
      postRequest = new Gson().fromJson(getJsonString(request), InterviewPostRequest.class);
    } catch (Exception JsonSyntaxException) {
      response.sendError(400);
      return;
    }
    if (!postRequest.allFieldsPopulated()) {
      response.sendError(400);
      return;
    }

    String interviewerCompany = postRequest.getCompany();
    String interviewerJob = postRequest.getJob();
    String utcStartTime = postRequest.getUtcStartTime();
    TimeRange range;

    try {
      range =
          new TimeRange(
              Instant.parse(utcStartTime), Instant.parse(utcStartTime).plus(1, ChronoUnit.HOURS));
    } catch (DateTimeParseException e) {
      response.sendError(400, e.getMessage());
      return;
    }

    List<Availability> availabilitiesInRange =
        availabilityDao.getInRangeForAll(range.start(), range.end());
    List<Person> allAvailableInterviewers =
        ShowInterviewersServlet.getPossiblePeople(
            personDao, availabilityDao, availabilitiesInRange, range);
    List<String> possibleInterviewers =
        getPossibleInterviewerIds(allAvailableInterviewers, interviewerCompany, interviewerJob);

    int randomNumber = (int) (Math.random() * possibleInterviewers.size());
    String interviewerId = possibleInterviewers.get(randomNumber);

    scheduledInterviewDao.create(
        ScheduledInterview.create(-1, range, interviewerId, intervieweeId));

    HashMap<String, String> emailedDetails = new HashMap<String, String>();
    String interviewId = getScheduledInterviewId(intervieweeId, range);
    String intervieweeFeedbackLink =
        String.format(
            "http://interview-me-step-2020.appspot.com/feedback.html?interview=%s&role=interviewee",
            interviewId);
    String interviewerFeedbackLink =
        String.format(
            "http://interview-me-step-2020.appspot.com/feedback.html?interview=%s&role=interviewee",
            interviewId);
    emailedDetails.put("{{formatted_date}}", getDateString(range));
    emailedDetails.put("{{interviewer_first_name}}", getFirstName(interviewerId));
    emailedDetails.put("{{interviewee_first_name}}", getFirstName(intervieweeId));
    emailedDetails.put("{{form_link}}", intervieweeFeedbackLink);

    try {
      sendIntervieweeEmail(intervieweeId, emailedDetails);
      emailedDetails.put("{{form_link}}", interviewerFeedbackLink);
      sendInterviewerEmail(interviewerId, emailedDetails);
    } catch (Exception e) {
      response.sendError(500);
      return;
    }

    // Since an interview was scheduled, both parties' availabilities must be updated
    List<Availability> affectedAvailability = new ArrayList<Availability>();
    List<Availability> intervieweeAffectedAvailability =
        availabilityDao.getInRangeForUser(intervieweeId, range.start(), range.end());
    List<Availability> interviewerAffectedAvailability =
        availabilityDao.getInRangeForUser(interviewerId, range.start(), range.end());
    affectedAvailability.addAll(intervieweeAffectedAvailability);
    affectedAvailability.addAll(interviewerAffectedAvailability);

    for (Availability avail : affectedAvailability) {
      availabilityDao.update(avail.withScheduled(true));
    }
  }

  // Get Json from request body.
  private static String getJsonString(HttpServletRequest request) throws IOException {
    BufferedReader reader = request.getReader();
    StringBuffer buffer = new StringBuffer();
    String payloadLine = null;

    while ((payloadLine = reader.readLine()) != null) buffer.append(payloadLine);
    return buffer.toString();
  }

  List<String> getPossibleInterviewerIds(List<Person> availablePeople, String company, String job) {
    List<String> possibleInterviewerIds = new ArrayList<String>();
    for (Person person : availablePeople) {
      if (person.company().equals(company) && person.job().equals(job)) {
        possibleInterviewerIds.add(person.id());
      }
    }
    return possibleInterviewerIds;
  }

  public List<ScheduledInterviewRequest> scheduledInterviewsToRequestObjects(
      List<ScheduledInterview> scheduledInterviews,
      String timeZoneIdString,
      String userTimeString) {
    ZoneId timeZoneId = ZoneId.of(timeZoneIdString);
    Instant userTime = Instant.parse(userTimeString);
    List<ScheduledInterviewRequest> requestObjects = new ArrayList<ScheduledInterviewRequest>();
    for (ScheduledInterview scheduledInterview : scheduledInterviews) {
      requestObjects.add(makeScheduledInterviewRequest(scheduledInterview, timeZoneId, userTime));
    }
    return requestObjects;
  }

  private String getDateString(TimeRange when, ZoneId timeZoneId) {
    LocalDateTime start = LocalDateTime.ofInstant(when.start(), timeZoneId);
    LocalDateTime end = LocalDateTime.ofInstant(when.end(), timeZoneId);
    String startTime = start.format(DateTimeFormatter.ofPattern("h:mm a"));
    String endTime = end.format(DateTimeFormatter.ofPattern("h:mm a"));
    String day = start.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
    return String.format("%s from %s to %s", day, startTime, endTime);
  }

  private ScheduledInterviewRequest makeScheduledInterviewRequest(
      ScheduledInterview scheduledInterview, ZoneId timeZoneId, Instant userTime) {
    String userEmail = userService.getCurrentUser().getEmail();
    String userId = getUserId();
    String date = getDateString(scheduledInterview.when(), timeZoneId);
    String interviewer = getFirstName(scheduledInterview.interviewerId());
    String interviewee = getFirstName(scheduledInterview.intervieweeId());
    String role = getUserRole(scheduledInterview, userId);
    boolean hasStarted =
        scheduledInterview.when().start().minus(5, ChronoUnit.MINUTES).isBefore(userTime);

    return new ScheduledInterviewRequest(
        scheduledInterview.id(), date, interviewer, interviewee, role, hasStarted);
  }

  static String getUserRole(ScheduledInterview scheduledInterview, String userId) {
    if (userId.equals(scheduledInterview.interviewerId())) {
      return "Interviewer";
    }
    if (userId.equals(scheduledInterview.intervieweeId())) {
      return "Interviewee";
    }
    return "unknown";
  }

  private String getUserId() {
    String userId = userService.getCurrentUser().getUserId();
    // Since Users returned from the LocalUserService (in tests) do not have userIds, here we set
    // the userId equal to a hashcode.
    if (userId == null) {
      userId = String.format("%d", userService.getCurrentUser().getEmail().hashCode());
    }
    return userId;
  }

  private String getDateString(TimeRange when) {
    LocalDateTime start = LocalDateTime.ofInstant(when.start(), ZoneId.systemDefault());
    String startTime = start.format(DateTimeFormatter.ofPattern("h:mm a"));
    String day = start.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
    return String.format("%s at %s UTC", day, startTime);
  }

  private String getEmail(String participantId) {
    return personDao.get(participantId).map(Person::email).orElse("Nonexistent User");
  }

  private String getFirstName(String participantId) {
    return personDao.get(participantId).map(Person::firstName).orElse("Nonexistent User");
  }

  private void sendInterviewerEmail(String interviewerId, HashMap<String, String> emailedDetails)
      throws IOException, Exception {
    EmailSender emailSender = new EmailSender(new Email("interviewme.business@gmail.com"));
    String subject = "You have been requested to conduct a mock interview!";
    Email recipient = new Email("grantflash@gmail.com");
    // Email recipient = new Email(getEmail(interviewerId));
    String contentString =
        emailSender.fileContentToString(emailsPath + "/NewInterview_Interviewer.txt");
    Content content =
        new Content("text/plain", emailSender.replaceAllPairs(emailedDetails, contentString));
    emailSender.sendEmail(recipient, subject, content);
  }

  private void sendIntervieweeEmail(String intervieweeId, HashMap<String, String> emailedDetails)
      throws IOException, Exception {
    EmailSender emailSender = new EmailSender(new Email("interviewme.business@gmail.com"));
    String subject = "You have been registered for a mock interview!";
    Email recipient = new Email("grantflash@gmail.com");
    // Email recipient = new Email(getEmail(intervieweeId));
    String contentString =
        emailSender.fileContentToString(emailsPath + "/NewInterview_Interviewee.txt");
    Content content =
        new Content("text/plain", emailSender.replaceAllPairs(emailedDetails, contentString));
    emailSender.sendEmail(recipient, subject, content);
  }

  private String getScheduledInterviewId(String userId, TimeRange range) {
    List<ScheduledInterview> possibleInterviews = scheduledInterviewDao.getForPerson(userId);
    for (ScheduledInterview scheduledInterview : possibleInterviews) {
      if (userId.equals(scheduledInterview.intervieweeId())
          && scheduledInterview.when().equals(range)) {
        return String.valueOf(scheduledInterview.id());
      }
    }
    return "";
  }
}
