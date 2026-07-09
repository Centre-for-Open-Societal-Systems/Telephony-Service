pipeline {
    agent any

    environment {
        AWS_REGION     = 'ap-northeast-1'
        AWS_ACCOUNT_ID = '379220350808'
        ECR_REGISTRY   = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        
        LEAD_SERVICE_REPO    = 'freeswitch-lead-service-ivr'
        EVENT_PUBLISHER_REPO = 'freeswitch-event-publisher-ivr'
        FREESWITCH_REPO      = 'freeswitch-freeswitch-ivr'
        
        // Jenkins credentials IDs
        AWS_CREDENTIALS_ID  = 'aws-credentials'
        KUBECONFIG_CRED_ID  = 'kubeconfig'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Java Artifacts') {
            steps {
                sh 'mvn clean package -DskipTests -f service/lead-service/pom.xml'
                sh 'mvn clean package -DskipTests -f service/event-publisher/pom.xml'
            }
        }

        stage('Docker Build & Tag') {
            steps {
                script {
                    sh "docker build -t ${ECR_REGISTRY}/${LEAD_SERVICE_REPO}:${BUILD_NUMBER} -t ${ECR_REGISTRY}/${LEAD_SERVICE_REPO}:latest ./service/lead-service"
                    sh "docker build -t ${ECR_REGISTRY}/${EVENT_PUBLISHER_REPO}:${BUILD_NUMBER} -t ${ECR_REGISTRY}/${EVENT_PUBLISHER_REPO}:latest ./service/event-publisher"
                    sh "docker build -t ${ECR_REGISTRY}/${FREESWITCH_REPO}:${BUILD_NUMBER} -t ${ECR_REGISTRY}/${FREESWITCH_REPO}:latest ./deploy/freeswitch"
                }
            }
        }

        stage('ECR Push') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: env.AWS_CREDENTIALS_ID,
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                ]]) {
                    sh "aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_REGISTRY}"
                    
                    sh "docker push ${ECR_REGISTRY}/${LEAD_SERVICE_REPO}:${BUILD_NUMBER}"
                    sh "docker push ${ECR_REGISTRY}/${LEAD_SERVICE_REPO}:latest"
                    
                    sh "docker push ${ECR_REGISTRY}/${EVENT_PUBLISHER_REPO}:${BUILD_NUMBER}"
                    sh "docker push ${ECR_REGISTRY}/${EVENT_PUBLISHER_REPO}:latest"
                    
                    sh "docker push ${ECR_REGISTRY}/${FREESWITCH_REPO}:${BUILD_NUMBER}"
                    sh "docker push ${ECR_REGISTRY}/${FREESWITCH_REPO}:latest"
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withCredentials([
                    file(credentialsId: env.KUBECONFIG_CRED_ID, variable: 'KUBECONFIG'),
                    [$class: 'AmazonWebServicesCredentialsBinding', credentialsId: env.AWS_CREDENTIALS_ID, accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
                ]) {
                    script {
                        def pgPass = 'lead_password'
                        def pgaPass = 'admin'
                        def eslPass = 'CluSt3r@Esl#2026!'
                        
                        try {
                            withCredentials([string(credentialsId: 'postgres-password', variable: 'PG_PASS_VAR')]) {
                                pgPass = env.PG_PASS_VAR
                            }
                        } catch (Exception e) {
                            echo "postgres-password credential not found in Jenkins, using default password"
                        }
                        
                        try {
                            withCredentials([string(credentialsId: 'pgadmin-password', variable: 'PGA_PASS_VAR')]) {
                                pgaPass = env.PGA_PASS_VAR
                            }
                        } catch (Exception e) {
                            echo "pgadmin-password credential not found in Jenkins, using default password"
                        }
                        
                        try {
                            withCredentials([string(credentialsId: 'freeswitch-esl-password', variable: 'ESL_PASS_VAR')]) {
                                eslPass = env.ESL_PASS_VAR
                            }
                        } catch (Exception e) {
                            echo "freeswitch-esl-password credential not found in Jenkins, using default password"
                        }

                        sh """
                            echo "==> Refreshing ECR registry credentials secret (regcred)..."
                            ECR_PASSWORD=\$(aws ecr get-login-password --region ${AWS_REGION})
                            /usr/local/bin/kubectl --kubeconfig \${KUBECONFIG} delete secret regcred --ignore-not-found
                            /usr/local/bin/kubectl --kubeconfig \${KUBECONFIG} create secret docker-registry regcred \\
                                --docker-server=\${ECR_REGISTRY} \\
                                --docker-username=AWS \\
                                --docker-password="\${ECR_PASSWORD}"
                            
                            echo "==> Deploying applications via Helm..."
                            helm upgrade --install telephony ./deploy/helm/telephony \\
                                --set global.registry=\${ECR_REGISTRY} \\
                                --set leadService.image.tag=${BUILD_NUMBER} \\
                                --set eventPublisher.image.tag=${BUILD_NUMBER} \\
                                --set freeswitch.image.tag=${BUILD_NUMBER} \\
                                --set postgres.password="${pgPass}" \\
                                --set pgadmin.password="${pgaPass}" \\
                                --set freeswitch.eslPassword="${eslPass}" \\
                                --kubeconfig \${KUBECONFIG}
                        """
                    }
                }
            }
        }
    }

    post {
        always {
            sh "docker rmi ${ECR_REGISTRY}/${LEAD_SERVICE_REPO}:${BUILD_NUMBER} || true"
            sh "docker rmi ${ECR_REGISTRY}/${EVENT_PUBLISHER_REPO}:${BUILD_NUMBER} || true"
            sh "docker rmi ${ECR_REGISTRY}/${FREESWITCH_REPO}:${BUILD_NUMBER} || true"
        }
    }
}
