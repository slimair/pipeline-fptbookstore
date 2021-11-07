def repo
pipeline {
    agent any
    triggers {
        pollSCM('* * * * *')
    }
    environment {
      DISCORD_WEBHOOK = credentials('discord-fptbook-webhook')
      KATALON_API_KEY = credentials('katalon-api-key')
      GIT_COMMIT_SHORT = ''
    }
    options {
      buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '10', daysToKeepStr: '', numToKeepStr: '10')
    }
    stages {
      stage('Notifi') {
        steps {
            discordSend description: "Jenkins Pipeline is starting", footer: "CI/CD Slimair.co", link: BUILD_URL, title: "Job \'${JOB_NAME}\' (${BUILD_NUMBER})", webhookURL: DISCORD_WEBHOOK
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
      stage('Checkout functional testing') {
          steps {
            echo 'Waiting for project is fully up and running'
            sleep(time:4,unit:"SECONDS")
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
                katalonStudio.inside ("--network FptBook") {
                sh """
                  cd katalon
                  katalonc.sh -projectPath=\$(pwd)/fptbookstore_katalon.prj -browserType='Firefox' -retry=0 -statusDelay=15 -testSuitePath='Test Suites/FptBook_TestSuite' -apiKey=${KATALON_API_KEY} --config -webui.autoUpdateDrivers=true --allowed-ips='137.184.131.91' --disable-dev-shm-usage  --no-sandbox
                """
            }
          }
        }
        post {
          always {
            sh '''
              docker rm -f FptBookTest || true
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
            docker rm -f prndotnet || true
            docker run -d --rm -v '/FptBook/image:/app/wwwroot/image' --network MASA -p 8888:80 --name prndotnet tiendvlp/prndotnet:latest
          """
        }
      }
    }
    post {
      always {
          echo 'Clean up workspace'
          discordSend description: "Jenkins Pipeline Build", footer: "CI/CD Slimair.co", link: BUILD_URL, result: currentBuild.result, title: "Job \'${JOB_NAME}\' (${BUILD_NUMBER}) ${currentBuild.result}", webhookURL: DISCORD_WEBHOOK
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
