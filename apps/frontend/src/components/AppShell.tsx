"use client";

import {
  AlertOutlined,
  ApartmentOutlined,
  AuditOutlined,
  BankOutlined,
  CopyOutlined,
  DashboardOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  LogoutOutlined,
  ProjectOutlined,
  QuestionCircleOutlined,
  SettingOutlined,
  TeamOutlined,
  UnorderedListOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Avatar, Dropdown, Flex, Layout, Menu, Select, Typography, theme } from "antd";
import type { MenuProps } from "antd";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { useRef, useTransition, type ReactNode } from "react";

import { setLocale } from "@/lib/actions/locale";
import type { NavKey } from "@/lib/auth/permissions";
import type { SessionUser } from "@/lib/auth/session-store";

const NAV: Record<Exclude<NavKey, "admin">, { href: string; icon: ReactNode }> = {
  projects: { href: "/", icon: <ProjectOutlined /> },
  dashboard: { href: "/dashboard", icon: <DashboardOutlined /> },
  requests: { href: "/requests", icon: <UnorderedListOutlined /> },
  dataRoom: { href: "/data-room", icon: <FolderOpenOutlined /> },
  findings: { href: "/findings", icon: <AlertOutlined /> },
  qna: { href: "/qna", icon: <QuestionCircleOutlined /> },
  reports: { href: "/reports", icon: <FileTextOutlined /> },
};

const ADMIN_NAV = [
  { key: "adminUsers", href: "/admin/users", icon: <TeamOutlined /> },
  { key: "adminOrganizations", href: "/admin/organizations", icon: <BankOutlined /> },
  { key: "adminGroups", href: "/admin/groups", icon: <ApartmentOutlined /> },
  { key: "adminTemplates", href: "/admin/templates", icon: <CopyOutlined /> },
  { key: "adminAudit", href: "/admin/audit", icon: <AuditOutlined /> },
] as const;

export function AppShell({
  user,
  nav,
  children,
}: Readonly<{ user: SessionUser; nav: NavKey[]; children: ReactNode }>) {
  const t = useTranslations();
  const locale = useLocale();
  const pathname = usePathname();
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const logoutForm = useRef<HTMLFormElement>(null);
  const { token } = theme.useToken();

  const items: MenuProps["items"] = nav.map((key) => {
    if (key === "admin") {
      return {
        key: "admin",
        icon: <SettingOutlined />,
        label: t("nav.admin"),
        children: ADMIN_NAV.map((item) => ({
          key: item.href,
          icon: item.icon,
          label: <Link href={item.href}>{t(`nav.${item.key}`)}</Link>,
        })),
      };
    }
    const item = NAV[key];
    return { key: item.href, icon: item.icon, label: <Link href={item.href}>{t(`nav.${key}`)}</Link> };
  });

  const selected =
    [...Object.values(NAV).map((n) => n.href), ...ADMIN_NAV.map((n) => n.href)]
      .filter((href) =>
        href === "/" ? pathname === "/" || pathname.startsWith("/projects/") : pathname.startsWith(href),
      )
      .sort((a, b) => b.length - a.length)[0] ?? "/";

  const userMenu: MenuProps["items"] = [
    { key: "profile", icon: <UserOutlined />, label: <Link href="/profile">{t("header.profile")}</Link> },
    { type: "divider" },
    {
      key: "logout",
      icon: <LogoutOutlined />,
      label: t("header.logout"),
      onClick: () => logoutForm.current?.requestSubmit(),
    },
  ];

  return (
    <Layout style={{ minHeight: "100vh" }}>
      <Layout.Sider breakpoint="lg" collapsedWidth={0} width={232} theme="light">
        <div style={{ padding: "20px 24px" }}>
          <Typography.Text strong style={{ fontSize: 16 }}>
            {t("app.name")}
          </Typography.Text>
        </div>
        <Menu
          mode="inline"
          items={items}
          selectedKeys={[selected]}
          defaultOpenKeys={selected.startsWith("/admin") ? ["admin"] : []}
          style={{ borderInlineEnd: 0 }}
        />
      </Layout.Sider>
      <Layout>
        <Layout.Header
          style={{
            background: token.colorBgContainer,
            paddingInline: 24,
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
          }}
        >
          <Flex justify="end" align="center" gap={16} style={{ height: "100%" }}>
            <Select
              aria-label={t("header.language")}
              value={locale}
              loading={pending}
              size="small"
              style={{ width: 72 }}
              options={[
                { value: "id", label: "ID" },
                { value: "en", label: "EN" },
              ]}
              onChange={(value) =>
                startTransition(async () => {
                  await setLocale(value);
                  router.refresh();
                })
              }
            />
            <Dropdown menu={{ items: userMenu }} trigger={["click"]}>
              <Flex align="center" gap={8} style={{ cursor: "pointer" }}>
                <Avatar size="small" icon={<UserOutlined />} />
                <Typography.Text>{user.name || user.email}</Typography.Text>
              </Flex>
            </Dropdown>
            <form ref={logoutForm} action="/auth/logout" method="post" hidden />
          </Flex>
        </Layout.Header>
        <Layout.Content style={{ padding: 24 }}>{children}</Layout.Content>
      </Layout>
    </Layout>
  );
}
