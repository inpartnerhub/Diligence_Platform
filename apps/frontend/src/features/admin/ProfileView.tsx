"use client";

import { Button, Card, Descriptions, Space, Tag } from "antd";
import { useFormatter, useTranslations } from "next-intl";

import { PageHeader } from "@/components/PageHeader";

import type { Profile } from "./types";

export function ProfileView({ profile, accountUrl }: Readonly<{ profile: Profile; accountUrl: string }>) {
  const t = useTranslations();
  const format = useFormatter();
  return (
    <>
      <PageHeader title={t("profile.title")} />
      <Card>
        <Descriptions column={1} bordered>
          <Descriptions.Item label={t("common.name")}>{profile.displayName}</Descriptions.Item>
          <Descriptions.Item label={t("common.email")}>{profile.email}</Descriptions.Item>
          <Descriptions.Item label={t("common.organization")}>
            {profile.organization?.name ?? t("common.none")}
          </Descriptions.Item>
          <Descriptions.Item label={t("profile.roles")}>
            <Space wrap>
              {profile.roles.map((role) => (
                <Tag key={role}>{t(`roles.${role}`)}</Tag>
              ))}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label={t("profile.mfa")}>
            {profile.mfaEnabled ? <Tag color="green">{t("profile.mfaOn")}</Tag> : t("profile.mfaOff")}
          </Descriptions.Item>
          <Descriptions.Item label={t("profile.lastLogin")}>
            {profile.lastLoginAt
              ? format.dateTime(new Date(profile.lastLoginAt), { dateStyle: "medium", timeStyle: "short" })
              : t("common.none")}
          </Descriptions.Item>
        </Descriptions>
        <Button href={accountUrl} target="_blank" rel="noopener noreferrer" style={{ marginTop: 16 }}>
          {t("profile.manageAccount")}
        </Button>
      </Card>
    </>
  );
}
