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

'use strict';

angular.module('zeppelinWebApp')
.controller('NavCtrl', function($scope, $rootScope, $http, $routeParams,
    $location, notebookListDataFactory, baseUrlSrv, websocketMsgSrv, arrayOrderingSrv, searchService) {

  $scope.query = {q : '' };
  /** Current list of notes (ids) */

  $scope.showLoginWindow = function() {
    setTimeout(function() {
      angular.element('#userName').focus();
    }, 500);
  };

  var vm = this;
  vm.notes = notebookListDataFactory;
  vm.connected = websocketMsgSrv.isConnected();
  vm.websocketMsgSrv = websocketMsgSrv;
  vm.arrayOrderingSrv = arrayOrderingSrv;
  $scope.searchForm = searchService;

  angular.element('#notebook-list').perfectScrollbar({suppressScrollX: true});

  angular.element(document).click(function(){
    $scope.query.q = '';
  });

  $scope.$on('setNoteMenu', function(event, notes) {
    notebookListDataFactory.setNotes(notes);
  });

  $scope.$on('setConnectedStatus', function(event, param) {
    vm.connected = param;
  });

  $scope.$on('loginSuccess', function(event, param) {
    loadNotes();
  });

  $scope.logout = function() {
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
             /* globals u, ActiveXObject */
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
          window.location.replace('/');
        }, 1000);
      });
    });
  };

  $scope.search = function(searchTerm) {
    $location.url(/search/ + searchTerm);
  };

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

  function loadNotes() {
    websocketMsgSrv.getNotebookList();
  }

  function isActive(noteId) {
    return ($routeParams.noteId === noteId);
  }

  $rootScope.noteName = function(note) {
    if (!_.isEmpty(note)) {
      return arrayOrderingSrv.getNoteName(note);
    }
  };

  function getZeppelinVersion() {
    $http.get(baseUrlSrv.getRestApiBase() + '/version').success(
      function(data, status, headers, config) {
        $rootScope.zeppelinVersion = data.body;
      }).error(
      function(data, status, headers, config) {
        console.log('Error %o %o', status, data.message);
      });
  }

  vm.loadNotes = loadNotes;
  vm.isActive = isActive;

  getZeppelinVersion();
  vm.loadNotes();

});
