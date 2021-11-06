def repo
def discord_webhook = "https://discord.com/api/webhooks/906416161237925938/oO7wfBXMZgAPYTUk-H1f2QOpQFw0uRBmjjG-zF1YSVEf1B7SyrSpuKQFYAGnYDeyyOQA"
pipeline {
    agent any
    triggers {
        pollSCM('* * * * *')
    }
    environment {
      VERSION = '0.1'
      GIT_COMMIT_SHORT = ''
    }
    options {
      buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '10', daysToKeepStr: '', numToKeepStr: '10')
    }
    stages {
      stage('Notifi') {
        steps {
            discordSend description: "Jenkins Pipeline is starting", footer: "CI/CD Slimair.co", link: BUILD_URL, title: "Job \'${JOB_NAME}\' (${BUILD_NUMBER})", webhookURL: discord_webhook
        }
      }
      stage('Setup') {
            steps {
                cleanWs()
                checkout scm
            }
        }
      stage('Checkout') {
        steps {
          echo 'Checking out process'
          dir('code') {
            script {
              repo = checkout([$class: 'GitSCM', branches: [[name: 'master']],
                  userRemoteConfigs: [[url: 'https://github.com/haicao2805/online-book-management']]])
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
          sh '''
            cp backend_config/appsettings.json code/FptBookStore
          '''
        }
      }
      stage('Build Image') {
        steps {
            sh """
            docker rmi -f \$(docker images | grep 'tiendvlp/prndotnet') || true
            docker build -f misc/Dockerfile -t tiendvlp/prndotnet:${GIT_COMMIT_SHORT} .
            docker build -f misc/Dockerfile -t tiendvlp/prndotnet:latest .
            """
        }
      }
      stage('Publish image on docker hub') {
        steps {
          script {
              withDockerRegistry([credentialsId: 'docker-hub', url: '' ]) {
                sh """
                      docker push tiendvlp/prndotnet:${GIT_COMMIT_SHORT}
                      docker rmi -f tiendvlp/prndotnet:${GIT_COMMIT_SHORT}
                      docker push tiendvlp/prndotnet:latest
                """
              }
          }
        }
      }
      stage ('Run test container') {
        steps {
            sh """
              docker network create FptBook || true
              docker run -d --rm -v '/FptBook/image:/app/wwwroot/image' --network MASA --name FptBookTest tiendvlp/prndotnet:latest
              docker network connect FptBook FptBookTest
            """
        }
      }
      //  stage('Run') {
      //   steps {
      //     sh """
      //       docker stop prndotnet || true
      //       docker run -d --rm -v '/FptBook/image:/app/wwwroot/image' --network MASA -p 8888:80 --name prndotnet tiendvlp/prndotnet:latest
      //     """
      //   }
      // }
      stage('Checkout functional testing') {
          steps {
            echo 'Waiting for project is fully up and running'
            sleep(time:10,unit:"SECONDS")
            echo 'Checkout the Katalon automation test'
            dir('katalon') {
              script {
                repo = checkout([$class: 'GitSCM', branches: [[name: 'main']],
                    userRemoteConfigs: [[url: 'https://github.com/slimair/fptbookstore-functional-testing']]])
              }
            }
          }
      }
      stage ('Functional testing') {
        steps {
            script {
              def katalonStudio = docker.image('katalonstudio/katalon');
              katalonStudio.pull();
              katalonStudio.inside ("--network FptBook --rm") {
                sh '''
                  cd katalon
                  katalonc.sh -projectPath=$(pwd)/fptbookstore_katalon.prj -browserType="Firefox" -retry=0 -statusDelay=15 -testSuitePath="Test Suites/FptBook_TestSuite" -apiKey=ba490008-3999-4890-848b-b43048e5ca92 --config -webui.autoUpdateDrivers=true --allowed-ips="137.184.131.91" --disable-dev-shm-usage  --no-sandbox
                '''
              }
          }
        }
        post {
          always {
            sh '''
              docker stop FptBookTest || true
              docker network rm FptBook || true
            '''
            dir ('katalon') {
               archiveArtifacts artifacts: 'Reports/**/*.*', fingerprint: true
               junit 'Reports/**/JUnit_Report.xml'
            }
          }
        }
      } 
      stage('Run') {
        steps {
          sh """
            docker stop prndotnet || true
            docker run -d --rm -v '/FptBook/image:/app/wwwroot/image' --network MASA -p 8888:80 --name prndotnet tiendvlp/prndotnet:latest
          """
        }
      }
    }
    post {
      always {
          echo 'Clean up workspace'
          discordSend description: "Jenkins Pipeline Build", footer: "CI/CD Slimair.co", link: BUILD_URL, result: currentBuild.result, title: "Job \'${JOB_NAME}\' (${BUILD_NUMBER}) ${currentBuild.result}", webhookURL: discord_webhook
          // cleanWs deleteDirs: true
      }
      changed {
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
