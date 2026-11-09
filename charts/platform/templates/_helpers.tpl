{{- define "platform.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/version: {{ .version | quote }}
app.kubernetes.io/part-of: seatstorm
{{- end -}}

{{- define "platform.imageTag" -}}
{{- index (splitList ":" .) 1 -}}
{{- end -}}

{{- define "platform.pullSecrets" -}}
{{- with .Values.imagePullSecrets }}
imagePullSecrets:
{{ toYaml . | indent 2 }}
{{- end }}
{{- end -}}
