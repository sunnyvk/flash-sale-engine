#!/usr/bin/env bash
# Creates the AWS resources from AWS CloudShell (or any shell with admin-ish CLI credentials). Idempotent enough to re-run.
#   REPO_URL=https://github.com/<you>/<repo>.git ./provision.sh
# Creates: IAM role + instance profile (SSM Session Manager, read /flashsale/prod/*, write backups), security group
# (80/443 inbound only, NO port 22), private S3 backup bucket (14-day expiry), EC2 instance (encrypted gp3, IMDSv2
# required), Elastic IP. Prints the IP to put in your DNS A record.
set -euo pipefail
: "${REPO_URL:?set REPO_URL to the https clone URL of the (public) repo}"
REGION=${AWS_REGION:-ap-south-1}
INSTANCE_TYPE=${INSTANCE_TYPE:-t3.large}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BUCKET=${BUCKET:-flashsale-backups-$ACCOUNT_ID-$REGION}
ROLE=flashsale-ec2-role
PROFILE=flashsale-ec2-profile
SG_NAME=flashsale-web
export AWS_DEFAULT_REGION=$REGION

echo "== IAM role and instance profile"
aws iam get-role --role-name "$ROLE" >/dev/null 2>&1 || \
  aws iam create-role --role-name "$ROLE" --assume-role-policy-document "file://$HERE/iam/ec2-trust.json" >/dev/null
aws iam attach-role-policy --role-name "$ROLE" --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
sed -e "s|\${REGION}|$REGION|g" -e "s|\${ACCOUNT_ID}|$ACCOUNT_ID|g" -e "s|\${BUCKET}|$BUCKET|g" \
  "$HERE/iam/instance-policy.json.template" > /tmp/instance-policy.json
aws iam put-role-policy --role-name "$ROLE" --policy-name flashsale-app --policy-document file:///tmp/instance-policy.json
aws iam get-instance-profile --instance-profile-name "$PROFILE" >/dev/null 2>&1 || {
  aws iam create-instance-profile --instance-profile-name "$PROFILE" >/dev/null
  aws iam add-role-to-instance-profile --instance-profile-name "$PROFILE" --role-name "$ROLE"
  sleep 10   # IAM is eventually consistent
}

echo "== S3 backup bucket (private, 14-day expiry)"
if ! aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  if [ "$REGION" = us-east-1 ]; then aws s3api create-bucket --bucket "$BUCKET" >/dev/null
  else aws s3api create-bucket --bucket "$BUCKET" --create-bucket-configuration "LocationConstraint=$REGION" >/dev/null; fi
fi
aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" --lifecycle-configuration \
  '{"Rules":[{"ID":"expire-14d","Status":"Enabled","Filter":{"Prefix":"mysql/"},"Expiration":{"Days":14}}]}'
aws ssm put-parameter --name /flashsale/prod/BACKUP_BUCKET --value "$BUCKET" --type String --overwrite >/dev/null

echo "== Security group (80/443 only)"
VPC_ID=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
SG_ID=$(aws ec2 describe-security-groups --filters Name=group-name,Values=$SG_NAME Name=vpc-id,Values="$VPC_ID" \
  --query 'SecurityGroups[0].GroupId' --output text)
if [ "$SG_ID" = None ]; then
  SG_ID=$(aws ec2 create-security-group --group-name $SG_NAME --description "flash-sale web: 80/443 only" \
    --vpc-id "$VPC_ID" --query GroupId --output text)
  for port in 80 443; do
    aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port $port --cidr 0.0.0.0/0 >/dev/null
  done
fi

echo "== EC2 instance"
AMI=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --query Parameter.Value --output text)
sed "s|__REPO_URL__|$REPO_URL|" "$HERE/bootstrap-instance.sh" > /tmp/user-data.sh
INSTANCE_ID=$(aws ec2 run-instances --image-id "$AMI" --instance-type "$INSTANCE_TYPE" \
  --iam-instance-profile Name=$PROFILE --security-group-ids "$SG_ID" \
  --metadata-options HttpTokens=required,HttpEndpoint=enabled,HttpPutResponseHopLimit=1 \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=30,VolumeType=gp3,Encrypted=true,DeleteOnTermination=true}' \
  --user-data file:///tmp/user-data.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=flashsale}]' \
  --query 'Instances[0].InstanceId' --output text)
aws ec2 wait instance-running --instance-ids "$INSTANCE_ID"

echo "== Elastic IP"
ALLOC=$(aws ec2 allocate-address --domain vpc --tag-specifications 'ResourceType=elastic-ip,Tags=[{Key=Name,Value=flashsale}]' \
  --query AllocationId --output text)
aws ec2 associate-address --instance-id "$INSTANCE_ID" --allocation-id "$ALLOC" >/dev/null
IP=$(aws ec2 describe-addresses --allocation-ids "$ALLOC" --query 'Addresses[0].PublicIp' --output text)

cat <<MSG

Instance:   $INSTANCE_ID  (type $INSTANCE_TYPE, region $REGION)
Elastic IP: $IP
Backups:    s3://$BUCKET

NEXT: create a DNS A record  <your-domain> -> $IP  (DuckDNS works), wait for it to resolve, then follow README step 4.
MSG
