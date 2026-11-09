{{- define "seatstorm.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/version: {{ .tag | quote }}
app.kubernetes.io/part-of: seatstorm
{{- end -}}
