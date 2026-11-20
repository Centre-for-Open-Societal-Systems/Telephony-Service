pipeline {
    agent any

    environment {
        AWS_REGION     = 'ap-northeast-1'
        AWS_ACCOUNT_ID = '379220350808'
        ECR_REGISTRY   = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        
        LEAD_SERVICE_REPO    = 'freeswitch-lead-service'
        EVENT_PUBLISHER_REPO = 'freeswitch-event-publisher'
        FREESWITCH_REPO      = 'freeswitch-freeswitch'

        // Second registry: ap-south-1, for the leads-api Helm chart (telephony subchart,
        // namespace `leads`). The same images are pushed to both. ap-northeast-1 above is
        // unchanged and is still what 'Deploy to Kubernetes' uses.
        // Paths follow the OAN naming standard (oan-helm docs/release/naming-standard.md):
        //   eoan/<repository>/<image>:<version>-develop.<BUILD_NUMBER>
        // grouped under leads-api, the chart that installs them.
        SOUTH_AWS_REGION           = 'ap-south-1'
        SOUTH_ECR_REGISTRY         = "${AWS_ACCOUNT_ID}.dkr.ecr.${SOUTH_AWS_REGION}.amazonaws.com"
        SOUTH_LEAD_SERVICE_REPO    = 'eoan/leads-api/lead-service'
        SOUTH_EVENT_PUBLISHER_REPO = 'eoan/leads-api/event-publisher'
        SOUTH_FREESWITCH_REPO      = 'eoan/leads-api/freeswitch'
        // Immutable version tag (no moving tags), e.g. 0.1.0-develop.24.
        SOUTH_TAG                  = "0.1.0-develop.${BUILD_NUMBER}"

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

                    // Same images tagged for ap-south-1 (no rebuild: both regions get identical images).
                    sh "docker tag ${ECR_REGISTRY}/${LEAD_SERVICE_REPO}:${BUILD_NUMBER} ${SOUTH_ECR_REGISTRY}/${SOUTH_LEAD_SERVICE_REPO}:${SOUTH_TAG}"
                    sh "docker tag ${ECR_REGISTRY}/${EVENT_PUBLISHER_REPO}:${BUILD_NUMBER} ${SOUTH_ECR_REGISTRY}/${SOUTH_EVENT_PUBLISHER_REPO}:${SOUTH_TAG}"
                    sh "docker tag ${ECR_REGISTRY}/${FREESWITCH_REPO}:${BUILD_NUMBER} ${SOUTH_ECR_REGISTRY}/${SOUTH_FREESWITCH_REPO}:${SOUTH_TAG}"
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

        // Build-number tags only (no :latest): the leads-api chart pins tag + digest.
        stage('ECR Push (ap-south-1)') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: env.AWS_CREDENTIALS_ID,
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                ]]) {
                    sh '''#!/usr/bin/env bash
                        set -euo pipefail
                        REPOS="$SOUTH_LEAD_SERVICE_REPO $SOUTH_EVENT_PUBLISHER_REPO $SOUTH_FREESWITCH_REPO"
                        # Create the repos on first use, with the eoan/ standard settings
                        # (immutable tags, scan on push, AES256, owner + naming tags).
                        for repo in $REPOS; do
                          aws ecr describe-repositories --region "$SOUTH_AWS_REGION" --repository-names "$repo" >/dev/null 2>&1 \\
                            || aws ecr create-repository --region "$SOUTH_AWS_REGION" --repository-name "$repo" \\
                                 --image-tag-mutability IMMUTABLE \\
                                 --image-scanning-configuration scanOnPush=true \\
                                 --encryption-configuration encryptionType=AES256 \\
                                 --tags Key=owner,Value=leads-api Key=naming,Value=eoan-standard >/dev/null
                        done
                        aws ecr get-login-password --region "$SOUTH_AWS_REGION" \\
                          | docker login --username AWS --password-stdin "$SOUTH_ECR_REGISTRY"
                        for repo in $REPOS; do
                          docker push "$SOUTH_ECR_REGISTRY/$repo:$SOUTH_TAG"
                        done
                        # Digests to pin in the leads-api chart (telephony.*.image.digest).
                        for repo in $REPOS; do
                          echo "$repo:$SOUTH_TAG $(docker inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$SOUTH_ECR_REGISTRY/$repo:$SOUTH_TAG" | grep "^$SOUTH_ECR_REGISTRY/$repo@" | cut -d@ -f2)"
                        done
                    '''
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
                            if [ ! -f ./helm ]; then
                                echo "==> Downloading Helm..."
                                curl -sSL https://get.helm.sh/helm-v3.14.0-linux-amd64.tar.gz | tar xz
                                mv linux-amd64/helm ./helm
                                rm -rf linux-amd64
                            fi
                            
                            echo "==> Refreshing ECR registry credentials secret (regcred)..."
                            ECR_PASSWORD=\$(aws ecr get-login-password --region ${AWS_REGION})
                            /usr/local/bin/kubectl --kubeconfig \${KUBECONFIG} delete secret regcred --ignore-not-found
                            /usr/local/bin/kubectl --kubeconfig \${KUBECONFIG} create secret docker-registry regcred \\
                                --docker-server=\${ECR_REGISTRY} \\
                                --docker-username=AWS \\
                                --docker-password="\${ECR_PASSWORD}"
                            
                            echo "==> Deploying applications via Helm..."
                            ./helm upgrade --install telephony ./deploy/helm/telephony \\
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
            sh "docker rmi ${SOUTH_ECR_REGISTRY}/${SOUTH_LEAD_SERVICE_REPO}:${SOUTH_TAG} || true"
            sh "docker rmi ${SOUTH_ECR_REGISTRY}/${SOUTH_EVENT_PUBLISHER_REPO}:${SOUTH_TAG} || true"
            sh "docker rmi ${SOUTH_ECR_REGISTRY}/${SOUTH_FREESWITCH_REPO}:${SOUTH_TAG} || true"
        }
    }
}
