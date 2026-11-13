# Short-lived credentials for the deploy workflow (#164): GitHub's OIDC token for this repository and GitHub
# environment is exchanged for this role; no long-lived AWS keys in GitHub. The role may only describe its cluster;
# inside the cluster it is the Kubernetes group `kubernetes_group`, whose rights are the namespaced RBAC in
# infrastructure/kubernetes/rbac. The GitHub OIDC provider exists once per AWS account (see the Terraform README).
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # Only jobs that run in this GitHub environment (its protection rules apply: prod needs approval).
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:${var.github_environment}"]
    }
  }
}

resource "aws_iam_role" "this" {
  name                 = var.name
  assume_role_policy   = data.aws_iam_policy_document.trust.json
  max_session_duration = 3600
  tags                 = var.tags
}

data "aws_iam_policy_document" "cluster" {
  statement {
    actions   = ["eks:DescribeCluster"]
    resources = [var.cluster_arn]
  }
}

resource "aws_iam_role_policy" "cluster" {
  name   = "${var.name}-describe-cluster"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.cluster.json
}

resource "aws_eks_access_entry" "this" {
  cluster_name      = var.cluster_name
  principal_arn     = aws_iam_role.this.arn
  kubernetes_groups = [var.kubernetes_group]
  type              = "STANDARD"
  tags              = var.tags
}
