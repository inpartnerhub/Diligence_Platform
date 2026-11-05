"use client";

import { App } from "antd";
import { useTranslations } from "next-intl";

import { stepUpLoginUrl } from "./stepUp";

/**
 * For sensitive actions (#65): when a server action failed because the last sign-in is too old, offer to sign in
 * again and come back to this page. Returns true when it handled the result.
 */
export function useStepUp() {
  const t = useTranslations("common");
  const { modal } = App.useApp();
  return (result: { ok: boolean; stepUp?: boolean }) => {
    if (result.ok || !result.stepUp) return false;
    modal.confirm({
      title: t("stepUpTitle"),
      content: t("stepUpBody"),
      okText: t("stepUpConfirm"),
      onOk: () =>
        window.location.assign(stepUpLoginUrl(`${window.location.pathname}${window.location.search}`)),
    });
    return true;
  };
}
