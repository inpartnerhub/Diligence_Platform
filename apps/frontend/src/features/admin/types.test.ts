import { describe, expect, it } from "vitest";

import type {
  WebhookDeliveryLog,
  WebhookSubscription,
  WebhookSubscriptionInput,
  WebhookTestResult,
} from "./types";

describe("admin webhook types", () => {
  it("structures WebhookSubscription correctly", () => {
    const sub: WebhookSubscription = {
      id: "sub-123",
      organizationId: "org-1",
      targetUrl: "https://example.com/webhook",
      signingKey: "test-signing-key-123",
      description: "Test webhook",
      subscribedEvents: "finding.*,report.issued",
      active: true,
      createdBy: "user-1",
      createdAt: "2026-03-30T10:00:00Z",
      updatedAt: "2026-03-30T10:00:00Z",
    };

    expect(sub.id).toBe("sub-123");
    expect(sub.active).toBe(true);
    expect(sub.subscribedEvents.split(",")).toEqual(["finding.*", "report.issued"]);
  });

  it("structures WebhookSubscriptionInput and logs correctly", () => {
    const input: WebhookSubscriptionInput = {
      targetUrl: "https://example.com/api",
      subscribedEvents: "*",
      active: true,
    };
    expect(input.targetUrl).toBe("https://example.com/api");

    const log: WebhookDeliveryLog = {
      id: "log-1",
      subscriptionId: "sub-123",
      eventType: "finding.status-changed",
      payload: "{}",
      statusCode: 200,
      durationMs: 45,
      success: true,
      errorMessage: null,
      attemptCount: 1,
      deliveredAt: "2026-03-30T10:00:00Z",
    };
    expect(log.success).toBe(true);
    expect(log.statusCode).toBe(200);

    const testResult: WebhookTestResult = {
      success: true,
      statusCode: 200,
      durationMs: 50,
      errorMessage: null,
      logId: "log-1",
    };
    expect(testResult.success).toBe(true);
  });
});
