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
import com.google.sps.data.DatastorePersonDao;
import com.google.sps.data.DatastoreScheduledInterviewDao;
import com.google.sps.data.EmailSender;
import com.google.sps.data.Person;
import com.google.sps.data.PersonDao;
import com.google.sps.data.ScheduledInterview;
import com.google.sps.data.ScheduledInterviewDao;
import com.google.sps.data.TimeRange;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;

// Servlet that gets the feedback for to an interviewer sends to an interviewee
@WebServlet("/interviewee-feedback")
public class IntervieweeFeedbackServlet extends HttpServlet {
  private ScheduledInterviewDao scheduledInterviewDao;
  private PersonDao personDao;
  private Path emailsPath =
      Paths.get(
          System.getProperty("user.home") + "/InterviewMe/src/main/resources/templates/email");

  @Override
  public void init() {
    init(new DatastoreScheduledInterviewDao(), new DatastorePersonDao());
  }

  public void init(ScheduledInterviewDao scheduledInterviewDao, PersonDao personDao) {
    this.scheduledInterviewDao = scheduledInterviewDao;
    this.personDao = personDao;
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    long scheduledInterviewId = Long.parseLong(request.getParameter("interviewId"));
    HashMap<String, String> answers = new HashMap<String, String>();
    answers.put("{{formatted_date}}", "Insert Date");
    for (int i = 0; i < 11; i++) {
      String number = Integer.toString(i + 1);
      answers.put("{{question_" + number + "}}", request.getParameter("question" + number));
    }

    String userEmail = UserServiceFactory.getUserService().getCurrentUser().getEmail();
    String userId = UserServiceFactory.getUserService().getCurrentUser().getUserId();
    // Since UserId does not have a valid Mock, if the id is null (as when testing), it will be
    // replaced with this hashcode.
    if (userId == null) {
      userId = String.format("%d", userEmail.hashCode());
    }

    if (interviewExists(scheduledInterviewId)) {
      if (isInterviewer(scheduledInterviewId, userId)) {
        try {
          sendFeedback(getIntervieweeEmail(scheduledInterviewId), answers);
        } catch (Exception e) {
          e.printStackTrace();
          response.sendError(500);
          return;
        }
        response.sendRedirect("/scheduled-interviews.html");
        return;
      } else {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
    } else {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
  }

  private boolean interviewExists(long scheduledInterviewId) {
    return scheduledInterviewDao.get(scheduledInterviewId).isPresent();
  }

  private boolean isInterviewer(long scheduledInterviewId, String userId) {
    ScheduledInterview scheduledInterview = scheduledInterviewDao.get(scheduledInterviewId).get();
    return scheduledInterview.interviewerId().equals(userId);
  }

  private String getIntervieweeEmail(long scheduledInterviewId) {
    ScheduledInterview scheduledInterview = scheduledInterviewDao.get(scheduledInterviewId).get();
    return personDao
        .get(scheduledInterview.intervieweeId())
        .map(Person::email)
        .orElse("Email not found");
  }

  private void sendFeedback(String intervieweeEmail, HashMap<String, String> answers)
      throws IOException, Exception {
    EmailSender emailSender = new EmailSender(new Email("interviewme.business@gmail.com"));
    String subject = "Your Interviewer has submitted some feedback for your interview!";
    Email recipient = new Email(intervieweeEmail);
    String contentString =
        emailSender.fileContentToString(emailsPath + "/feedbackToInterviewee.txt");
    Content content =
        new Content("text/plain", emailSender.replaceAllPairs(answers, contentString));
    emailSender.sendEmail(recipient, subject, content);
  }
}
