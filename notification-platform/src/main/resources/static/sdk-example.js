const PLATFORM_URL = "http://localhost:8080";

export async function submitEvent({ type, eventId, data }) {
  const response = await fetch(`${PLATFORM_URL}/api/events`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-App-Id": "demo-order-service",
      "X-Api-Key": "order-key",
      "X-Trace-Id": eventId || crypto.randomUUID()
    },
    body: JSON.stringify({ type, eventId, data })
  });
  if (!response.ok) {
    throw new Error(`Submit event failed: ${response.status}`);
  }
  return response.json();
}
