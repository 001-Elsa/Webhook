export async function submitEvent({ platformUrl, appId, apiKey, type, eventId, data }) {
  const response = await fetch(`${platformUrl}/api/events`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-App-Id": appId,
      "X-Api-Key": apiKey,
      "X-Trace-Id": eventId || crypto.randomUUID()
    },
    body: JSON.stringify({ type, eventId, data })
  });
  if (!response.ok) {
    throw new Error(`Submit event failed: ${response.status}`);
  }
  return response.json();
}
