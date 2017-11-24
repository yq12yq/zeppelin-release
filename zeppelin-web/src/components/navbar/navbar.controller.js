/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

angular.module('zeppelinWebApp').controller('NavCtrl', NavCtrl);

NavCtrl.$inject = [
  '$scope',
  '$rootScope',
  '$http',
  '$routeParams',
  '$location',
  'noteListDataFactory',
  'baseUrlSrv',
  'websocketMsgSrv',
  'arrayOrderingSrv',
  'searchService',
  'TRASH_FOLDER_ID'
];

function NavCtrl($scope, $rootScope, $http, $routeParams, $location,
                 noteListDataFactory, baseUrlSrv, websocketMsgSrv,
                 arrayOrderingSrv, searchService, TRASH_FOLDER_ID) {
  var vm = this;
  vm.arrayOrderingSrv = arrayOrderingSrv;
  vm.connected = websocketMsgSrv.isConnected();
  vm.isActive = isActive;
  vm.logout = logout;
  vm.notes = noteListDataFactory;
  vm.search = search;
  vm.searchForm = searchService;
  vm.showLoginWindow = showLoginWindow;
  vm.TRASH_FOLDER_ID = TRASH_FOLDER_ID;
  vm.isFilterNote = isFilterNote;
  vm.numberOfNotesDisplayed = 10;

  $scope.query = {q: ''};

  initController();

  function getZeppelinVersion() {
    $http.get(baseUrlSrv.getRestApiBase() + '/version').success(
      function(data, status, headers, config) {
        $rootScope.zeppelinVersion = data.body;
      }).error(
      function(data, status, headers, config) {
        console.log('Error %o %o', status, data.message);
      });
  }

  function initController() {
    $scope.isDrawNavbarNoteList = false;
    angular.element('#notebook-list').perfectScrollbar({suppressScrollX: true});

    angular.element(document).click(function() {
      $scope.query.q = '';
    });

    getZeppelinVersion();
    loadNotes();
  }

  function isFilterNote(note) {
    if (!$scope.query.q) {
      return true;
    }

    var noteName = note.name;
    if (noteName.toLowerCase().indexOf($scope.query.q.toLowerCase()) > -1) {
      return true;
    }
    return false;
  }

  function isActive(noteId) {
    return ($routeParams.noteId === noteId);
  }

  function listConfigurations() {
    websocketMsgSrv.listConfigurations();
  }

  function loadNotes() {
    websocketMsgSrv.getNoteList();
  }

  function getHomeNote(){
    websocketMsgSrv.getHomeNote();
  }

  function logout() {
    var logoutURL = baseUrlSrv.getRestApiBase() + '/login/logout';

    $http.post(logoutURL).error(function() {

      //force authcBasic (if configured) to logout
      if (detectIE()) {
        var outcome
        try {
          outcome = document.execCommand('ClearAuthenticationCache')
        } catch (e) {
          console.log(e)
        }
        if (!outcome) {
          // Let's create an xmlhttp object
          outcome = (function (x) {
            if (x) {
              // the reason we use "random" value for password is
              // that browsers cache requests. changing
              // password effectively behaves like cache-busing.
              x.open('HEAD', location.href, true, 'logout',
                (new Date()).getTime().toString())
              x.send('')
              // x.abort()
              return 1 // this is **speculative** "We are done."
            } else {
              // eslint-disable-next-line no-useless-return
              return
            }
          })(window.XMLHttpRequest ? new window.XMLHttpRequest()
            // eslint-disable-next-line no-undef
            : (window.ActiveXObject ? new ActiveXObject('Microsoft.XMLHTTP') : u))
        }
        if (!outcome) {
          var m = 'Your browser is too old or too weird to support log out functionality. Close all windows and ' +
            'restart the browser.'
          alert(m)
        }
      } else {
        // for firefox and safari
        logoutURL = logoutURL.replace('//', '//false:false@')
      }

      $http.post(logoutURL).error(function() {
        $rootScope.userName = '';
        $rootScope.ticket.principal = '';
        $rootScope.ticket.ticket = '';
        $rootScope.ticket.roles = '';
        BootstrapDialog.show({
          message: 'Logout Success'
        });
        setTimeout(function() {
          window.location = baseUrlSrv.getBase();
        }, 1000);
      });
    });
  }

  function detectIE() {
    var ua = window.navigator.userAgent

    var msie = ua.indexOf('MSIE ')
    if (msie > 0) {
      // IE 10 or older => return version number
      return parseInt(ua.substring(msie + 5, ua.indexOf('.', msie)), 10)
    }

    var trident = ua.indexOf('Trident/')
    if (trident > 0) {
      // IE 11 => return version number
      var rv = ua.indexOf('rv:')
      return parseInt(ua.substring(rv + 3, ua.indexOf('.', rv)), 10)
    }

    var edge = ua.indexOf('Edge/')
    if (edge > 0) {
      // Edge (IE 12+) => return version number
      return parseInt(ua.substring(edge + 5, ua.indexOf('.', edge)), 10)
    }

    // other browser
    return false
  }

  function search(searchTerm) {
    $location.path('/search/' + searchTerm);
  }

  function showLoginWindow() {
    setTimeout(function() {
      angular.element('#userName').focus();
    }, 500);
  }

  /*
   ** $scope.$on functions below
   */

  $scope.$on('setNoteMenu', function(event, notes) {
    noteListDataFactory.setNotes(notes);
    initNotebookListEventListener();
  });

  $scope.$on('setConnectedStatus', function(event, param) {
    vm.connected = param;
  });

  $scope.$on('loginSuccess', function(event, param) {
    listConfigurations();
    loadNotes();
    getHomeNote();
  });

  /*
   ** Performance optimization for Browser Render.
   */
  function initNotebookListEventListener() {
    angular.element(document).ready(function() {
      angular.element('.notebook-list-dropdown').on('show.bs.dropdown', function() {
        $scope.isDrawNavbarNoteList = true;
      });

      angular.element('.notebook-list-dropdown').on('hide.bs.dropdown', function() {
        $scope.isDrawNavbarNoteList = false;
      });
    });
  }

  $scope.loadMoreNotes = function () {
    vm.numberOfNotesDisplayed += 10
  }
}
