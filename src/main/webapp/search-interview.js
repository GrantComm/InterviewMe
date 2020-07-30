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

function onSearchInterviewLoad() {
  const loginInfo = getLoginInfo();
  loginInfo.then(supplyLogoutLinkOrRedirectHome); 
  loginInfo.then(getUserOrRedirectRegistration);
}

// Queries Datastore for available interview times and renders them on the
// page.
function loadInterviews() {
  const searchResultsDiv = document.getElementById("search-results");
  searchResultsDiv.removeAttribute("hidden");
  const position = document.getElementById('position').value;
  // TODO: Include this position in the search process.

  fetch(`/load-interviews?timeZoneOffset=${browserTimezoneOffset()}`)
  .then(response => response.text())
  .then(interviewTimes => {
    interviewTimesDiv().innerHTML = interviewTimes;
  });
}

function interviewTimesDiv() {
  return document.getElementById('interview-times-container');
}

// Confirms interview selection with user and sends this selection to Datastore 
// if confirmed.
function selectInterview(interviewer) {
  let date = interviewer.getAttribute('data-date');
  let time = interviewer.getAttribute('data-time');
  let company = interviewer.getAttribute('data-company');
  let job = interviewer.getAttribute('data-job');
  let utcStartTime = interviewer.getAttribute('data-utc');
  if (confirm(
      `You selected: ${date} from ${time} with a ` +
      `${company} ${job}. ` +
      `Click OK if you wish to proceed.`)) {
    alert(
      `You have scheduled an interview on ${date}` +
      ` from ${time} with a ${company} ` +
      `${job}. Check your email for more ` +
      `information.`);
    let requestObject = {
      company: company,
      job: job,
      utcStartTime: utcStartTime
    };
    let requestBody = JSON.stringify(requestObject);
    let request = new Request('/scheduled-interviews', {method: 'POST', body: requestBody});
    fetch(request).then(unused => {window.location.replace('/scheduled-interviews.html');});
  }
}

// Fills in the modal with interviewer info from Datastore and shows it.
function showInterviewers(selectButton) {
  const date = selectButton.getAttribute('data-date');
  const select = document.getElementById(date);
  const time = select.options[select.selectedIndex].text;
  const reformattedTime = time.replace('-', 'to');
  const utc = select.value;
  fetch(`/show-interviewers?utcStartTime=${utc}&date=${date}&time=${reformattedTime}`)
  .then(response => response.text())
  .then(interviewers => {
    $('#modal-body').html(interviewers);
    $('#modal-title').text(`Interviewers Information for ${date} from ${reformattedTime}`);
  });
  $('#interviewer-modal').modal('show');
}
