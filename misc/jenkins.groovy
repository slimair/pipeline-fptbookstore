def repo
def FUNCTION_TESTING_REPORT_FILE_NAME = 'FunctionalTestingReports'
pipeline {
    agent any
    triggers {
        // check every minutes
        pollSCM('* * * * *')
    }
    environment {
      // Replace the credentials with your webhook url, and Katalon api key
      DISCORD_WEBHOOK = credentials('discord-fptbook-webhook')
      KATALON_API_KEY = credentials('katalon-api-key')
      APPSETTINGS_FILE_PATH = credentials('fptbook-appsetting');
      GIT_COMMIT_SHORT = ''
    }
    options {
      // maximum 10 artifacts to be kept
      buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '10', daysToKeepStr: '', numToKeepStr: '10')
    }
    stages {
      stage('Prepare') {
          steps {
              cleanWs()
              echo 'Send message to Discord to tell that the pipeline has been started'
//               discordSend description: "Jenkins Pipeline is starting", footer: "CI/CD Slimair.co", result: currentBuild.result, link: BUILD_URL, title: "Job \'${JOB_NAME}\' (${BUILD_NUMBER})", webhookURL: DISCORD_WEBHOOK
          }
      }
      stage ('Checkout the Pipeline') {
        steps {
          checkout scm
        }
      }
      stage('Checkout the FptBook project') {
        steps {
          echo 'Checking out process'
          // clone the project and put it to the code folder
          dir('code') {
            script {
              repo = checkout([$class: 'GitSCM', branches: [[name: 'master']],
                  userRemoteConfigs: [[url: 'https://github.com/haicao2805/online-book-management']]])
                  // get the short commit id
                  GIT_COMMIT_SHORT = sh(
                      script: "printf \$(git rev-parse --short ${repo.GIT_COMMIT})",
                      returnStdout: true
                  )
            }
            echo "Git commit short hash: ${GIT_COMMIT_SHORT}"
          }
        }
      }
      stage('Setup environment') {
        steps {
          sh """
            cp ${APPSETTINGS_FILE_PATH} code/FptBookStore
          """
        }
      }
      stage('Build Image') {
        steps {
            sh """
            docker build -f misc/Dockerfile -t tiendvlp/prndotnet:${GIT_COMMIT_SHORT} .
            docker build -f misc/Dockerfile -t tiendvlp/prndotnet:latest .
            """
        }
      }
//       stage ('Run test container') {
//         steps {
//             sh """
//               docker network create FptBook || true
//               docker run -d --rm -v '/FptBook/image:/app/wwwroot/image' --network MASA --name FptBookTest tiendvlp/prndotnet:latest
//               docker network connect FptBook FptBookTest
//             """
//         }
//       }
//       stage('Checkout code test') {
//           steps {
//             echo 'Waiting for project is fully up and running'
//             sleep(time:4,unit:"SECONDS")
//             echo 'Checkout the Katalon automation test'
//             dir('katalon') {
//               script {
//                 repo = checkout([$class: 'GitSCM', branches: [[name: 'main']],
//                     userRemoteConfigs: [[url: 'https://github.com/slimair/fptbookstore-functional-testing']]])
//               }
//             }
//           }
//       }
//       stage ('Start functional testing process') {
//         steps {
//             script {
//                 def katalonStudio = docker.image('katalonstudio/katalon');
//                 katalonStudio.pull();
//                 katalonStudio.inside ("--network FptBook --name KatalonStudio") {
//                 sh """
//                   cd katalon
//                   katalonc.sh -projectPath=\$(pwd)/fptbookstore_katalon.prj -browserType='Firefox' -retry=0 -statusDelay=15 -testSuitePath='Test Suites/FptBook_TestSuite' -apiKey=${KATALON_API_KEY} --config -webui.autoUpdateDrivers=true --allowed-ips='137.184.131.91' --disable-dev-shm-usage  --no-sandbox
//                 """
//             }
//           }
//         }
//         post {
//           always {
//             sh '''
//               docker rm -f KatalonStudio || true
//               docker rm -f FptBookTest || true
//               docker network rm FptBook || true
//             '''
//             dir ('katalon') {
//                archiveArtifacts artifacts: 'Reports/**/*.*', fingerprint: true
//                junit 'Reports/**/JUnit_Report.xml'
//             }
//           }
//         }
//       }
      stage('Publish image on docker hub') {
        steps {
          script {
              withDockerRegistry([credentialsId: 'docker-hub', url: '' ]) {
                sh """
                      docker push tiendvlp/prndotnet:${GIT_COMMIT_SHORT}
                      docker push tiendvlp/prndotnet:latest
                """
              }
          }
        }
        post {
          always {
            sh """
              docker rmi -f tiendvlp/prndotnet:${GIT_COMMIT_SHORT}
            """
          }
        }
      }
      stage('Run') {
        steps {
          sh """
            docker rm -f prndotnet || true
            docker run -d --rm -v '/FptBook/image:/app/wwwroot/image' --network MASA -p 8888:80 --name prndotnet tiendvlp/prndotnet:latest
          """
        }
      }
    }
    post {
//       always {
//         echo 'Publish the Katalon report file'
//         dir ('katalon') {
//           script {
//             /*
//             * Read the reports folder and publish the report file (html file)
//             */
//             def files = findFiles(glob: '**/*.html');
//               if (files.length > 0) {
//                 def file = files[0];
//                 def reportDir = file.getPath().substring(0, file.getPath().lastIndexOf('/'));
//                 def htmlFileName = file.getName();
//                 sh """
//                   echo 'ReportDir: ${reportDir}'
//                   echo 'ReportFile: ${htmlFileName}' 
//                 """
//                 publishHTML (target : [allowMissing: false,
//                   alwaysLinkToLastBuild: true,
//                   keepAll: true,
//                   reportDir: reportDir,
//                   reportFiles: htmlFileName,
//                   reportName: FUNCTION_TESTING_REPORT_FILE_NAME,
//                   reportTitles: 'Function testing Reports']);
//               }
//           }
//       }
//             discordSend (
//               description: """
//               Jenkins Pipeline build result: 
//               Functional testing reports: ${JOB_URL}/${FUNCTION_TESTING_REPORT_FILE_NAME}""",
//               footer: "CI/CD Slimair.co",
//               link: BUILD_URL,
//               result: currentBuild.result, 
//               title: "Job \'${JOB_NAME}\' (${BUILD_NUMBER}) ${currentBuild.result}", 
//               webhookURL: DISCORD_WEBHOOK)
//     }
      changed {
          // Only send email if the result is different from the previous build
          emailext subject: "Job \'${JOB_NAME}\' (${BUILD_NUMBER}) ${currentBuild.result}",
                body: "Please go to ${BUILD_URL} and verify it.", 
                attachLog: true, 
                compressLog: true, 
                from: 'Slimair.System',
                to: 'test@gmail',
                recipientProviders: [upstreamDevelopers(), requestor(), buildUser()] 
      }
    }
}
