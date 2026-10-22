#!/usr/bin/env bash
# Deletes everything provision.sh created (does NOT touch SSM parameters or the backup bucket unless asked).
#   ./teardown.sh            instance, Elastic IP, security group, instance profile/role
#   DELETE_DATA=1 ./teardown.sh   also the backups bucket and the SSM parameters
set -euo pipefail
REGION=${AWS_REGION:-ap-south-1}
export AWS_DEFAULT_REGION=$REGION
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
BUCKET=${BUCKET:-flashsale-backups-$ACCOUNT_ID-$REGION}

IDS=$(aws ec2 describe-instances --filters Name=tag:Name,Values=flashsale Name=instance-state-name,Values=pending,running,stopped,stopping \
  --query 'Reservations[].Instances[].InstanceId' --output text)
if [ -n "$IDS" ]; then
  # shellcheck disable=SC2086
  aws ec2 terminate-instances --instance-ids $IDS >/dev/null
  # shellcheck disable=SC2086
  aws ec2 wait instance-terminated --instance-ids $IDS
fi
for alloc in $(aws ec2 describe-addresses --filters Name=tag:Name,Values=flashsale --query 'Addresses[].AllocationId' --output text); do
  aws ec2 release-address --allocation-id "$alloc"
done
SG_ID=$(aws ec2 describe-security-groups --filters Name=group-name,Values=flashsale-web --query 'SecurityGroups[0].GroupId' --output text)
[ "$SG_ID" != None ] && aws ec2 delete-security-group --group-id "$SG_ID"
aws iam remove-role-from-instance-profile --instance-profile-name flashsale-ec2-profile --role-name flashsale-ec2-role 2>/dev/null || true
aws iam delete-instance-profile --instance-profile-name flashsale-ec2-profile 2>/dev/null || true
aws iam delete-role-policy --role-name flashsale-ec2-role --policy-name flashsale-app 2>/dev/null || true
aws iam detach-role-policy --role-name flashsale-ec2-role --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore 2>/dev/null || true
aws iam delete-role --role-name flashsale-ec2-role 2>/dev/null || true

if [ -n "${DELETE_DATA:-}" ]; then
  aws s3 rb "s3://$BUCKET" --force || true
  for n in $(aws ssm get-parameters-by-path --path /flashsale/prod/ --query 'Parameters[].Name' --output text); do
    aws ssm delete-parameter --name "$n"
  done
fi
echo "Teardown finished."
