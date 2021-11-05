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
      stage('Publish') {
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
            echo 'Checkout the Katalon automation test'
            dir('katalon') {
              script {
                repo = checkout([$class: 'GitSCM', branches: [[name: 'main']],
                    userRemoteConfigs: [[url: 'https://github.com/phuongnguyen521/SWT301-Katalon-FptBook']]])
              }
              sh 'docker build -t tiendvlp/katalon .'
              sh 'docker run tiendvlp/katalon'
              // sh 'docker run -t -v "$(pwd)":/tmp/project katalonstudio/katalon katalonc.sh -projectPath=/tmp/project/ -browserType="Chrome" -retry=0 -statusDelay=15 -testSuitePath="Test Suites/TS_RegressionTest" -apiKey=ba490008-3999-4890-848b-b43048e5ca92'
            }
          }
      }
    }
    post {
      always {
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
