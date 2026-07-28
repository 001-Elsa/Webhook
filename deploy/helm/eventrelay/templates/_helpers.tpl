{{- define "eventrelay.name" -}}eventrelay{{- end }}
{{- define "eventrelay.labels" -}}
app.kubernetes.io/name: {{ include "eventrelay.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "eventrelay.image" -}}
{{ .Values.image.repository }}:{{ default .Chart.AppVersion .Values.image.tag }}
{{- end }}
