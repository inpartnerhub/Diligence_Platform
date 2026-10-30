// k6 smoke test through the gateway. Run: k6 run -e BASE_URL=http://localhost:8000 -e TOKEN=<access token> smoke.js
import http from "k6/http";
import { check } from "k6";

export const options = {
  vus: 5,
  duration: "30s",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
  },
};

export default function () {
  const res = http.get(`${__ENV.BASE_URL}/api/v1/me`, {
    headers: { Authorization: `Bearer ${__ENV.TOKEN}` },
  });
  check(res, { "status is 200": (r) => r.status === 200 });
}
