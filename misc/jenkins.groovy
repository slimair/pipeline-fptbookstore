def repo
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
            docker stop prndotnet || true
            docker rmi \$(docker images | grep 'tiendvlp/prndotnet') || true
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
                      docker rmi tiendvlp/prndotnet:${GIT_COMMIT_SHORT}
                      docker push tiendvlp/prndotnet:latest
                """
              }
          }
        }
      }
      stage('Run') {
        steps {
          sh """
            docker run -d --rm -v '/FptBook/image:/app/wwwroot/image' --network MASA -p 8888:80 --name prndotnet tiendvlp/prndotnet:latest
          """
        }
      }
      stage('Functional testing') {
          steps {
            echo 'Waiting for project is fully up and running'
            sleep(time:25,unit:"SECONDS")
            echo 'Checkout the Katalon automation test'
            dir('katalon') {
              script {
                repo = checkout([$class: 'GitSCM', branches: [[name: 'main']],
                    userRemoteConfigs: [[url: 'https://github.com/slimair/fptbookstore-functional-testing']]])
              }
              sh 'docker-compose up'
            }
          }
      }
    }
    post {
      always {
          dir ('katalon') {
            sh 'docker-compose down'
          }
          echo 'Clean up workspace'
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
